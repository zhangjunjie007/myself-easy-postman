package com.laker.postman.service.swagger;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.laker.postman.collection.model.CollectionParseResult;
import com.laker.postman.util.MessageKeys;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Loads Swagger/OpenAPI JSON documents without using business authentication or Swing. */
@UtilityClass
public class SwaggerImportService {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .proxySelector(new DocumentProxySelector(ProxySelector.getDefault())).build();

    /**
     * Reads a selected local file or HTTP(S) document off the EDT, bounded to 8 MiB.
     * Redirects are supported; relative API servers use the final document URL. Download errors
     * expose safe translation keys/HTTP status, never signed URLs or credentials. Local/private IPs bypass OS proxies.
     * @param source absolute file URI or HTTP(S) JSON document URL
     * @return parsed requests, contract baselines and environments; failures abort before modifying collections
     */
    public static CollectionParseResult load(URI source) throws IOException {
        byte[] bytes;
        HttpUrl documentUrl = null;
        if ("file".equalsIgnoreCase(source.getScheme())) {
            try (var input = Files.newInputStream(Path.of(source))) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
        } else {
            if ((!"http".equalsIgnoreCase(source.getScheme()) && !"https".equalsIgnoreCase(source.getScheme()))
                    || source.getHost() == null || source.getUserInfo() != null)
                throw new IOException("collections.import.swagger.url_invalid");
            try (var response = CLIENT.newCall(new Request.Builder().url(source.toString()).build()).execute()) {
                if (!response.isSuccessful()) throw new HttpStatusException(response.code());
                if (response.body() == null) throw new IOException();
                documentUrl = response.request().url();
                try (var input = response.body().byteStream()) {
                    bytes = input.readNBytes(MAX_BYTES + 1);
                }
            } catch (HttpStatusException exception) {
                throw exception;
            } catch (InterruptedIOException exception) {
                throw new IOException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_TIMEOUT);
            } catch (UnknownHostException exception) {
                throw new IOException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_DNS_FAILED);
            } catch (ConnectException exception) {
                throw new IOException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_CONNECT_FAILED);
            } catch (SSLException exception) {
                throw new IOException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_TLS_FAILED);
            } catch (IOException | IllegalArgumentException exception) {
                // Raw client exceptions can contain signed URLs. Preserve the safe category, not secret-bearing text.
                throw new IOException("collections.import.swagger.download_failed");
            }
        }
        if (bytes.length > MAX_BYTES) throw new IOException("collections.import.swagger.too_large");
        String json = new String(bytes, StandardCharsets.UTF_8);
        try {
            if (documentUrl != null) json = resolveRemoteServers(json, documentUrl);
            CollectionParseResult result = SwaggerParser.parseSwagger(json);
            if (result == null) throw new IllegalArgumentException();
            SwaggerRequestContract.attach(result, json, source);
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("collections.import.swagger.invalid");
        }
    }

    /** HTTP failure metadata safe for notifications/logs, without the remote body or potentially signed URL. */
    @Getter
    public static final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        /** Retains the server status for the UI; response bodies and raw client causes are deliberately omitted. */
        private HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** Document-specific proxy routing; ordinary API requests and system proxy preferences remain independent. */
    static final class DocumentProxySelector extends ProxySelector {
        private final ProxySelector systemSelector;

        /** Wraps the current OS selector; private literals are identified without extra DNS lookups. */
        DocumentProxySelector(ProxySelector systemSelector) {
            this.systemSelector = systemSelector;
        }

        /** Re-evaluates every redirect host, bypassing the proxy for local/private IPs and localhost only. */
        @Override
        public List<Proxy> select(URI uri) {
            return isLocalAddress(uri.getHost()) || systemSelector == null
                    ? List.of(Proxy.NO_PROXY) : systemSelector.select(uri);
        }

        /** Preserves proxy failure reporting for the OS selector on proxied public requests. */
        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            if (systemSelector != null) systemSelector.connectFailed(uri, address, exception);
        }

        /** Covers private IPv4, loopback/link-local IPv6 and ULA literals; hostnames keep the OS routing policy. */
        private static boolean isLocalAddress(String host) {
            if (host == null) return false;
            if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) return true;
            if (!host.matches("[0-9.]+") && !host.contains(":")) return false;
            try {
                InetAddress address = InetAddress.getByName(host);
                byte[] bytes = address.getAddress();
                return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
            } catch (UnknownHostException exception) {
                return false;
            }
        }
    }

    /** Supplies omitted Swagger hosts and resolves relative OpenAPI server URLs before existing parsing. */
    private static String resolveRemoteServers(String json, HttpUrl documentUrl) {
        JSONObject root = JSONUtil.parseObj(json);
        if (root.containsKey("swagger")) {
            if (root.getStr("host", "").isBlank()) root.set("host", documentUrl.uri().getRawAuthority());
            if (root.getJSONArray("schemes") == null || root.getJSONArray("schemes").isEmpty())
                root.set("schemes", new JSONArray().put(documentUrl.scheme()));
        } else if (root.containsKey("openapi")) {
            JSONArray servers = root.getJSONArray("servers");
            if (servers == null || servers.isEmpty()) {
                servers = new JSONArray().put(new JSONObject().set("url", "/"));
                root.set("servers", servers);
            }
            for (int index = 0; index < servers.size(); index++) {
                JSONObject server = servers.getJSONObject(index);
                String url = server.getStr("url", "/");
                if (!url.matches("(?i)^https?://.*")) {
                    HttpUrl resolved = documentUrl.resolve(url);
                    if (resolved == null) throw new IllegalArgumentException();
                    server.set("url", resolved.toString().replace("%7B", "{").replace("%7D", "}"));
                }
            }
        }
        return root.toString();
    }
}
