package com.laker.postman.http.runtime.okhttp;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * Keeps JVM-wide SOCKS settings from overriding the route selected by OkHttp.
 * OkHttp creates SOCKS sockets itself; it uses this factory for direct routes
 * and for the TCP connection to an HTTP proxy.
 */
final class JvmProxyBypassingSocketFactory extends SocketFactory {
    static final SocketFactory INSTANCE = new JvmProxyBypassingSocketFactory();

    /** Shared factory; no per-client mutable state or global proxy changes. */
    private JvmProxyBypassingSocketFactory() {
    }

    /** Creates an unconnected direct socket; OkHttp applies its own route and timeouts. */
    @Override
    public Socket createSocket() {
        return new Socket(Proxy.NO_PROXY);
    }

    /** Connects to the host directly without inheriting JVM SOCKS settings. */
    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    /** Binds the requested local endpoint before making a direct connection. */
    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    /** Connects directly to the resolved address without invoking the JVM proxy selector. */
    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    /** Connects a resolved remote endpoint using the caller's local binding. */
    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    /** Returns a connected direct socket; failed binds/connections close it before propagating the failure. */
    private static Socket connect(SocketAddress remote, SocketAddress local) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            if (local != null) {
                socket.bind(local);
            }
            socket.connect(remote);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
}
