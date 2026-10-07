package com.laker.postman.platform.instance;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Coordinates one GUI process per data directory.
 *
 * <p>The lock file is intentionally allowed to remain on disk. Ownership is
 * determined only by the operating-system file lock, which is released when
 * the owning channel closes or the process terminates.</p>
 */
@Slf4j
public final class SingleInstanceCoordinator implements AutoCloseable {
    private static final String RUNTIME_DIRECTORY_NAME = ".runtime";
    private static final String LOCK_FILE_NAME = "gui-instance.lock";
    private static final String METADATA_FILE_NAME = "gui-instance.properties";
    private static final String PROTOCOL_VERSION = "1";
    private static final String COMMAND_ACTIVATE = "ACTIVATE";
    private static final String COMMAND_SHUTDOWN = "SHUTDOWN";
    private static final String RESPONSE_OK = "OK";
    private static final String RESPONSE_DENIED = "DENIED";
    private static final String RESPONSE_ERROR = "ERROR";
    private static final Duration COORDINATION_TIMEOUT = Duration.ofMillis(1800);
    private static final Duration TAKEOVER_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration GRACEFUL_EXIT_TIMEOUT = Duration.ofSeconds(3);
    private static final int CONNECT_TIMEOUT_MILLIS = 300;
    private static final int SOCKET_READ_TIMEOUT_MILLIS = 500;
    private static final int RETRY_DELAY_MILLIS = 75;
    private static final int TOKEN_BYTES = 32;
    private static final Pattern LEGACY_GUI_COMMAND = Pattern.compile(
            "(?:^|\\s)\\\"?com\\.laker\\.postman\\.App\\\"?\\s*$|"
                    + "(?i)(?:^|\\s)-jar\\s+(?:\\\"[^\\\"]*easy[-_]?postman[^\\\"\\\\/]*\\.jar\\\""
                    + "|[^\\s]*easy[-_]?postman[^\\s\\\\/]*\\.jar)\\s*$");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final FileChannel lockChannel;
    private final FileLock instanceLock;
    private final ServerSocket activationServer;
    private final Path metadataPath;
    private final String token;
    private final Runnable activationHandler;
    private final Runnable shutdownHandler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread listenerThread;

    /** Retains OS lock ownership and host callbacks until this coordinator is closed. */
    private SingleInstanceCoordinator(FileChannel lockChannel,
                                      FileLock instanceLock,
                                      ServerSocket activationServer,
                                      Path metadataPath,
                                      String token,
                                      Runnable activationHandler,
                                      Runnable shutdownHandler) {
        this.lockChannel = lockChannel;
        this.instanceLock = instanceLock;
        this.activationServer = activationServer;
        this.metadataPath = metadataPath;
        this.token = token;
        this.activationHandler = activationHandler;
        this.shutdownHandler = shutdownHandler;
    }

    /**
     * Acquires GUI ownership for the supplied data directory or asks the
     * existing owner to activate its window.
     */
    public static LaunchResult acquireOrNotify(Path dataRoot, Runnable activationHandler) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(activationHandler, "activationHandler");

        Path runtimeDirectory = dataRoot.toAbsolutePath().normalize().resolve(RUNTIME_DIRECTORY_NAME);
        Files.createDirectories(runtimeDirectory);
        restrictPermissions(runtimeDirectory, OWNER_DIRECTORY_PERMISSIONS);

        Path lockPath = runtimeDirectory.resolve(LOCK_FILE_NAME);
        Path metadataPath = runtimeDirectory.resolve(METADATA_FILE_NAME);
        long deadlineNanos = System.nanoTime() + COORDINATION_TIMEOUT.toNanos();

        while (true) {
            LockOwnership ownership = tryAcquireOwnership(lockPath);
            if (ownership != null) {
                return startPrimary(ownership, metadataPath, activationHandler);
            }
            if (notifyExistingInstance(metadataPath)) {
                return LaunchResult.existingInstanceNotified();
            }
            if (System.nanoTime() >= deadlineNanos) {
                return LaunchResult.existingInstanceUnreachable();
            }
            if (!pauseBeforeRetry()) {
                return LaunchResult.existingInstanceUnreachable();
            }
        }
    }

    /**
     * Makes this launch the GUI owner of {@code dataRoot}, replacing its previous owner.
     * The old GUI has three seconds to save and exit before its process tree is terminated.
     * PID/start-time checks exclude reused PIDs; unverifiable owners are never terminated.
     * Callbacks must enqueue their UI work and return promptly. No other data directory is scanned.
     *
     * @return the new primary owner, or unreachable if its lock cannot safely be reclaimed
     */
    public static LaunchResult acquireOrReplace(Path dataRoot, Runnable activationHandler,
                                                Runnable shutdownHandler) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(activationHandler, "activationHandler");
        Objects.requireNonNull(shutdownHandler, "shutdownHandler");
        Path runtimeDirectory = dataRoot.toAbsolutePath().normalize().resolve(RUNTIME_DIRECTORY_NAME);
        Files.createDirectories(runtimeDirectory);
        restrictPermissions(runtimeDirectory, OWNER_DIRECTORY_PERMISSIONS);
        Path metadataPath = runtimeDirectory.resolve(METADATA_FILE_NAME);
        long deadlineNanos = System.nanoTime() + TAKEOVER_TIMEOUT.toNanos();

        // Serialize replacement launches so two newcomers cannot terminate the same owner concurrently.
        LockOwnership takeover = null;
        while (takeover == null) {
            takeover = tryAcquireOwnership(runtimeDirectory.resolve("gui-takeover.lock"));
            if (takeover == null && (System.nanoTime() >= deadlineNanos || !pauseBeforeRetry())) {
                return LaunchResult.existingInstanceUnreachable();
            }
        }
        try (FileChannel channel = takeover.channel(); FileLock lock = takeover.lock()) {
            while (System.nanoTime() < deadlineNanos) {
                LockOwnership ownership = tryAcquireOwnership(runtimeDirectory.resolve(LOCK_FILE_NAME));
                if (ownership != null) {
                    return startPrimary(ownership, metadataPath, activationHandler, shutdownHandler);
                }
                InstanceMetadata metadata = readMetadata(metadataPath);
                ProcessHandle owner = verifiedOwner(metadata);
                if (owner != null) {
                    // Capture children before shutdown, since parent exit can orphan them on Windows.
                    List<ProcessHandle> descendants = owner.descendants()
                            .filter(process -> process.pid() != ProcessHandle.current().pid()).toList();
                    log.info("Replacing existing EasyPostman GUI process: pid={}", owner.pid());
                    if (sendCommand(metadata, COMMAND_SHUTDOWN)) {
                        waitForExit(List.of(owner), GRACEFUL_EXIT_TIMEOUT);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return LaunchResult.existingInstanceUnreachable();
                    }
                    if (owner.isAlive()) {
                        if (verifiedOwner(metadata) == null) {
                            throw new IOException("GUI process identity changed during replacement");
                        }
                        descendants = Stream.concat(descendants.stream(), owner.descendants())
                                .filter(process -> process.pid() != ProcessHandle.current().pid()).distinct().toList();
                        log.warn("Force-terminating previous GUI process tree: pid={}", owner.pid());
                        terminate(owner);
                    }
                    for (ProcessHandle descendant : descendants) {
                        terminate(descendant);
                    }
                    if (!waitForExit(descendants, Duration.ofSeconds(2))) {
                        throw new IOException("Previous GUI child processes did not terminate");
                    }
                }
                if (!pauseBeforeRetry()) {
                    break;
                }
            }
        }
        return LaunchResult.existingInstanceUnreachable();
    }

    /** Returns only the recorded live owner; legacy metadata additionally requires a GUI command line. */
    private static ProcessHandle verifiedOwner(InstanceMetadata metadata) {
        if (metadata == null || metadata.pid() <= 0 || metadata.pid() == ProcessHandle.current().pid()) {
            return null;
        }
        ProcessHandle process = ProcessHandle.of(metadata.pid()).orElse(null);
        if (process == null || !process.isAlive()) {
            return null;
        }
        ProcessHandle.Info info = process.info();
        Instant startedAt = info.startInstant().orElse(null);
        if (startedAt == null) {
            return null;
        }
        if (!metadata.startedAt().isBlank()) {
            return metadata.startedAt().equals(startedAt.toString())
                    && metadata.command().equals(info.command().orElse("")) ? process : null;
        }
        // Old releases recorded only PID. A process started after that file cannot be its owner.
        return startedAt.toEpochMilli() <= metadata.modifiedAtMillis() && isLegacyGuiProcess(process)
                ? process : null;
    }

    /** Recognizes an old GUI entry point, excluding the app's CLI commands and generic Java services. */
    private static boolean isLegacyGuiProcess(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String[] arguments = info.arguments().orElse(new String[0]);
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i].replace('\\', '/');
            String name = argument.substring(argument.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)
                    .replace("-", "").replace("_", "");
            if ("com.laker.postman.App".equals(arguments[i])
                    || (i > 0 && "-jar".equals(arguments[i - 1])
                    && name.startsWith("easypostman") && name.endsWith(".jar"))) {
                return i == arguments.length - 1;
            }
        }
        String command = info.command().orElse("").toLowerCase(Locale.ROOT).replace("-", "");
        String commandLine = info.commandLine().orElseGet(() -> readWindowsCommandLine(process.pid()));
        if (LEGACY_GUI_COMMAND.matcher(commandLine).find()) {
            return true;
        }
        return command.endsWith("easypostman.exe")
                && (commandLine.trim().equalsIgnoreCase(info.command().orElse(""))
                || commandLine.trim().equalsIgnoreCase('"' + info.command().orElse("") + '"'));
    }

    /**
     * Java 17 on Windows omits arguments/commandLine. Query only the recorded legacy PID via
     * Windows CIM, with a bounded wait; failure leaves the process untouched.
     */
    private static String readWindowsCommandLine(long pid) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return "";
        }
        Process query = null;
        try {
            query = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command",
                    "(Get-CimInstance Win32_Process -Filter 'ProcessId = " + pid + "').CommandLine")
                    .redirectErrorStream(true).start();
            if (query.waitFor(3, TimeUnit.SECONDS) && query.exitValue() == 0) {
                return new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException exception) {
            log.debug("Unable to identify legacy GUI command line: pid={}", pid, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (query != null && query.isAlive()) {
                query.destroyForcibly();
            }
        }
        return "";
    }

    /** Terminates a verified owner or its captured descendant; never terminates this launcher. */
    private static void terminate(ProcessHandle process) throws IOException {
        if (process.pid() != ProcessHandle.current().pid() && process.isAlive()
                && !process.destroyForcibly() && process.isAlive()) {
            throw new IOException("Unable to terminate previous GUI process: " + process.pid());
        }
    }

    /** Waits within a single shared deadline for captured processes; preserves interruption. */
    private static boolean waitForExit(List<ProcessHandle> processes, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (processes.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadlineNanos || !pauseBeforeRetry()) {
                return false;
            }
        }
        return true;
    }

    private static LockOwnership tryAcquireOwnership(Path lockPath) throws IOException {
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        restrictPermissions(lockPath, OWNER_FILE_PERMISSIONS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new LockOwnership(channel, lock);
        } catch (OverlappingFileLockException ignored) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel);
            throw exception;
        }
    }

    /** Starts the legacy activation-only owner without enabling remote shutdown. */
    private static LaunchResult startPrimary(LockOwnership ownership,
                                             Path metadataPath,
                                             Runnable activationHandler) throws IOException {
        return startPrimary(ownership, metadataPath, activationHandler, null);
    }

    /** Starts the lock owner; the optional shutdown callback must return without waiting for the UI. */
    private static LaunchResult startPrimary(LockOwnership ownership,
                                             Path metadataPath,
                                             Runnable activationHandler,
                                             Runnable shutdownHandler) throws IOException {
        ServerSocket activationServer = null;
        try {
            activationServer = new ServerSocket();
            InetAddress loopbackAddress = InetAddress.getLoopbackAddress();
            activationServer.bind(new InetSocketAddress(loopbackAddress, 0), 8);
            String token = generateToken();
            SingleInstanceCoordinator coordinator = new SingleInstanceCoordinator(
                    ownership.channel(),
                    ownership.lock(),
                    activationServer,
                    metadataPath,
                    token,
                    activationHandler,
                    shutdownHandler
            );
            coordinator.startListener();
            coordinator.writeMetadata(loopbackAddress);
            log.info("Acquired GUI instance lock for data directory: {}",
                    metadataPath.getParent().getParent());
            return LaunchResult.primary(coordinator);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(activationServer);
            releaseQuietly(ownership.lock());
            closeQuietly(ownership.channel());
            throw exception;
        }
    }

    private void startListener() {
        listenerThread = new Thread(this::listenForActivationRequests, "SingleInstanceActivationListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenForActivationRequests() {
        while (!closed.get()) {
            try {
                Socket socket = activationServer.accept();
                handleActivationRequest(socket);
            } catch (SocketException exception) {
                if (!closed.get()) {
                    log.warn("Single-instance activation server stopped unexpectedly", exception);
                }
                return;
            } catch (IOException exception) {
                if (!closed.get()) {
                    log.warn("Failed to receive single-instance activation request", exception);
                }
            }
        }
    }

    /** Authenticates local activation/shutdown requests; OK acknowledges enqueueing, not UI completion. */
    private void handleActivationRequest(Socket socket) {
        try (socket;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);
            String command = input.readUTF();
            String suppliedToken = input.readUTF();
            Runnable handler = COMMAND_ACTIVATE.equals(command) ? activationHandler
                    : COMMAND_SHUTDOWN.equals(command) ? shutdownHandler : null;
            if (handler == null || !tokensMatch(token, suppliedToken)) {
                output.writeUTF(RESPONSE_DENIED);
                output.flush();
                return;
            }
            try {
                handler.run();
                output.writeUTF(RESPONSE_OK);
            } catch (RuntimeException exception) {
                log.warn("Failed to handle existing GUI request: {}", command, exception);
                output.writeUTF(RESPONSE_ERROR);
            }
            output.flush();
        } catch (IOException exception) {
            if (!closed.get()) {
                log.debug("Single-instance activation client disconnected", exception);
            }
        }
    }

    /** Atomically publishes the local endpoint and process identity while holding the GUI lock. */
    private void writeMetadata(InetAddress loopbackAddress) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("protocolVersion", PROTOCOL_VERSION);
        properties.setProperty("host", loopbackAddress.getHostAddress());
        properties.setProperty("port", Integer.toString(activationServer.getLocalPort()));
        properties.setProperty("token", token);
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        ProcessHandle.Info processInfo = ProcessHandle.current().info();
        properties.setProperty("startedAt", processInfo.startInstant().map(Instant::toString).orElse(""));
        properties.setProperty("command", processInfo.command().orElse(""));

        Path temporaryPath = Files.createTempFile(metadataPath.getParent(), "gui-instance-", ".tmp");
        try {
            restrictPermissions(temporaryPath, OWNER_FILE_PERMISSIONS);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporaryPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                properties.store(writer, "EasyPostman GUI instance");
            }
            moveAtomically(temporaryPath, metadataPath);
            restrictPermissions(metadataPath, OWNER_FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }

    /** Sends the legacy activation request to the current recorded owner. */
    private static boolean notifyExistingInstance(Path metadataPath) {
        return sendCommand(readMetadata(metadataPath), COMMAND_ACTIVATE);
    }

    /** Sends an authenticated local request with bounded socket waits, including to older releases. */
    private static boolean sendCommand(InstanceMetadata metadata, String command) {
        if (metadata == null) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(metadata.address(), metadata.port()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                 DataInputStream input = new DataInputStream(socket.getInputStream())) {
                output.writeUTF(command);
                output.writeUTF(metadata.token());
                output.flush();
                return RESPONSE_OK.equals(input.readUTF());
            }
        } catch (IOException exception) {
            log.debug("Existing GUI instance is not ready to receive {}", command, exception);
            return false;
        }
    }

    /** Reads a validated loopback endpoint and optional legacy-compatible process identity. */
    private static InstanceMetadata readMetadata(Path metadataPath) {
        if (!Files.isRegularFile(metadataPath)) {
            return null;
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            if (!PROTOCOL_VERSION.equals(properties.getProperty("protocolVersion"))) {
                return null;
            }
            InetAddress address = InetAddress.getByName(properties.getProperty("host", ""));
            if (!address.isLoopbackAddress()) {
                log.warn("Ignored non-loopback single-instance endpoint: {}", address);
                return null;
            }
            int port = Integer.parseInt(properties.getProperty("port", ""));
            String token = properties.getProperty("token", "");
            if (port < 1 || port > 65535 || token.isBlank()) {
                return null;
            }
            return new InstanceMetadata(address, port, token,
                    Long.parseLong(properties.getProperty("pid", "0")),
                    properties.getProperty("startedAt", ""), properties.getProperty("command", ""),
                    Files.getLastModifiedTime(metadataPath).toMillis());
        } catch (IOException | IllegalArgumentException exception) {
            log.debug("Single-instance metadata is not ready or is invalid", exception);
            return null;
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean tokensMatch(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and some network file systems do not expose POSIX permissions.
        } catch (IOException exception) {
            log.debug("Unable to restrict single-instance file permissions: {}", path, exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(activationServer);
        joinListener();
        try {
            Files.deleteIfExists(metadataPath);
        } catch (IOException exception) {
            log.debug("Unable to remove single-instance metadata: {}", metadataPath, exception);
        }
        releaseQuietly(instanceLock);
        closeQuietly(lockChannel);
    }

    private void joinListener() {
        if (listenerThread == null || listenerThread == Thread.currentThread()) {
            return;
        }
        try {
            listenerThread.join(SOCKET_READ_TIMEOUT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
        } catch (IOException exception) {
            log.debug("Unable to release GUI instance lock", exception);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            log.debug("Unable to close single-instance resource", exception);
        }
    }

    public enum LaunchStatus {
        PRIMARY,
        EXISTING_INSTANCE_NOTIFIED,
        EXISTING_INSTANCE_UNREACHABLE
    }

    public record LaunchResult(LaunchStatus status, SingleInstanceCoordinator coordinator) {
        private static LaunchResult primary(SingleInstanceCoordinator coordinator) {
            return new LaunchResult(LaunchStatus.PRIMARY, coordinator);
        }

        private static LaunchResult existingInstanceNotified() {
            return new LaunchResult(LaunchStatus.EXISTING_INSTANCE_NOTIFIED, null);
        }

        private static LaunchResult existingInstanceUnreachable() {
            return new LaunchResult(LaunchStatus.EXISTING_INSTANCE_UNREACHABLE, null);
        }

        public boolean isPrimary() {
            return status == LaunchStatus.PRIMARY;
        }
    }

    private record LockOwnership(FileChannel channel, FileLock lock) {
    }

    /** Identity belongs to the data-directory lock owner; empty start time denotes an older release. */
    private record InstanceMetadata(InetAddress address, int port, String token, long pid,
                                    String startedAt, String command, long modifiedAtMillis) {
    }
}
