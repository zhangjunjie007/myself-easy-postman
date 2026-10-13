package com.laker.postman.startup;

import cn.hutool.json.JSONUtil;
import com.laker.postman.model.Workspace;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import java.util.Set;

/** Seeds persistent GUI profiles without initializing cached application settings paths. */
@UtilityClass
class GuiInstanceProfile {
    private static final String ROOT_TOKEN = "$EASY_POSTMAN_DATA$";
    private static final String INITIALIZED_PROFILE = "ready-v2";

    /**
     * Copies existing saved configuration once while the caller owns the destination's GUI lock.
     * Workspace paths (including external ones) are rebased to private copies; completed profiles
     * are never refreshed from the original, so later edits survive closing and reopening.
     * New copies and first upgrades disable inherited WebDAV auto-upload once to protect the original backup.
     */
    static void initialize(Path originalRoot, Path instanceRoot) throws IOException {
        Path ready = instanceRoot.resolve(".runtime/profile-initialized");
        if (Files.exists(ready)) {
            // Upgrade only the original cloning version once; never re-copy private data or undo
            // auto-upload that the user explicitly enables after configuring this isolated profile.
            if (!INITIALIZED_PROFILE.equals(Files.readString(ready, StandardCharsets.UTF_8).trim())) {
                disableClonedAutoUpload(instanceRoot);
                Files.writeString(ready, INITIALIZED_PROFILE, StandardCharsets.UTF_8);
            }
            return;
        }
        copyDirectory(originalRoot, instanceRoot, originalRoot);
        Path workspaceFile = originalRoot.resolve("workspaces.json");
        if (Files.exists(workspaceFile)) {
            var workspaces = JSONUtil.parseArray(Files.readString(workspaceFile)).toList(Workspace.class);
            for (int index = 0; index < workspaces.size(); index++) {
                Workspace workspace = workspaces.get(index);
                String stored = workspace.getPath();
                if (stored == null || stored.isBlank()) continue;
                Path source = stored.startsWith(ROOT_TOKEN)
                        ? originalRoot.resolve(stored.substring(ROOT_TOKEN.length()).replaceFirst("^[\\\\/]+", ""))
                        : originalRoot.resolve(stored);
                source = source.toAbsolutePath().normalize();
                Path relative = source.startsWith(originalRoot)
                        ? originalRoot.relativize(source) : Path.of("workspaces", "external-" + index);
                Path destination = instanceRoot.resolve(relative).normalize();
                if (!destination.startsWith(instanceRoot) || source.startsWith(originalRoot.resolve("instances")))
                    throw new IOException("Invalid workspace path for independent instance");
                if (!source.startsWith(originalRoot)) copyDirectory(source, destination, originalRoot);
                workspace.setPath(ROOT_TOKEN + "/" + relative.toString().replace('\\', '/'));
            }
            Files.writeString(instanceRoot.resolve("workspaces.json"), JSONUtil.toJsonPrettyStr(workspaces), StandardCharsets.UTF_8);
        }
        disableClonedAutoUpload(instanceRoot);
        Files.writeString(ready, INITIALIZED_PROFILE, StandardCharsets.UTF_8);
    }

    /** Pauses inherited auto-upload on creation/first upgrade, retaining other settings and the original profile. */
    private static void disableClonedAutoUpload(Path instanceRoot) throws IOException {
        Path settingsFile = instanceRoot.resolve("easy_postman_settings.properties");
        if (!Files.exists(settingsFile)) return;
        Properties settings = new Properties();
        try (var reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            settings.load(reader);
        }
        if (!Boolean.parseBoolean(settings.getProperty("webdav_sync_auto_upload_enabled", "false"))) return;
        settings.setProperty("webdav_sync_auto_upload_enabled", "false");
        try (var writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
            settings.store(writer, null);
        }
    }

    /**
     * Copies regular files without following links or cloning runtime locks, logs, and other profiles.
     * @param dataRoot original application data; an external ancestor workspace must not clone it again
     */
    private static void copyDirectory(Path source, Path destination, Path dataRoot) throws IOException {
        if (!Files.exists(source)) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /** Keeps profile/runtime directories out of the first-copy snapshot. */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                // An external workspace can contain the data root and thus this new destination.
                // Skip application data/destination subtrees instead of recursively copying our own output.
                if (directory.startsWith(destination) || (!directory.equals(source) && directory.equals(dataRoot)))
                    return FileVisitResult.SKIP_SUBTREE;
                Path relative = source.relativize(directory);
                if (relative.getNameCount() > 0 && Set.of(".runtime", "logs", "instances")
                        .contains(relative.getName(0).toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            /** Copies file contents only; symbolic links abort instead of sharing mutable data. */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) throw new IOException("Linked configuration files cannot be cloned safely");
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
