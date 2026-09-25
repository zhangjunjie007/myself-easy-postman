package com.laker.postman.service;

import cn.hutool.json.JSONUtil;
import com.laker.postman.http.runtime.model.PreparedRequest;
import com.laker.postman.model.Environment;
import com.laker.postman.model.PlmAuthConfig;
import com.laker.postman.request.model.HttpHeader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** PLM token login for the selected environment; credentials are configured there, tokens stay in memory. */
public final class PlmEnvironmentAuthService {
    private static Environment authenticatedEnvironment;
    private static String token;
    private static Instant expiresAt;

    /** Static service; only one selected environment supplies credentials at a time. */
    private PlmEnvironmentAuthService() {
    }

    /** Validates only the fields required by the chosen protocol; never includes credential values in errors. */
    public static void validate(PlmAuthConfig auth) {
        if (auth == null) {
            return;
        }
        String type = required(auth.getType(), "type");
        required(auth.getTokenUrl(), "tokenUrl");
        required(auth.getTokenJsonPath(), "tokenJsonPath");
        required(auth.getHeaderName(), "headerName");
        required(auth.getHeaderPrefix(), "headerPrefix");
        required(auth.getMethod(), "method");
        switch (type) {
            case "jit-token" -> {
                requirePost(auth);
                required(auth.getClientId(), "clientId");
                required(auth.getClientSecret(), "clientSecret");
                if (!"jit".equals(required(auth.getGrantType(), "grantType"))) {
                    throw new IllegalArgumentException("JIT grantType 必须为 jit");
                }
                required(auth.getPrincipalHeader(), "principalHeader");
                required(auth.getPrincipalPrefix(), "principalPrefix");
                required(auth.getPrincipal(), "principal");
            }
            case "password-token" -> {
                requirePost(auth);
                required(auth.getClientId(), "clientId");
                required(auth.getClientSecret(), "clientSecret");
                required(auth.getUsername(), "username");
                required(auth.getPassword(), "password");
                if (!"password".equals(required(auth.getGrantType(), "grantType"))) {
                    throw new IllegalArgumentException("用户名密码 grantType 必须为 password");
                }
            }
            case "pin-token" -> {
                required(auth.getPin(), "pin");
                if (!List.of("body", "header", "query")
                        .contains(required(auth.getPinLocation(), "pinLocation").toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("pinLocation 必须为 body/header/query");
                }
                required(auth.getPinName(), "pinName");
                if (auth.getBodyTemplate() != null) {
                    Object template;
                    try {
                        template = auth.getBodyTemplate() instanceof String json
                                ? JSONUtil.parse(json) : auth.getBodyTemplate();
                    } catch (RuntimeException exception) {
                        throw new IllegalArgumentException("bodyTemplate 不是有效 JSON");
                    }
                    if (!(template instanceof Map<?, ?>) && !(template instanceof List<?>)) {
                        throw new IllegalArgumentException("bodyTemplate 必须是 JSON 对象或数组");
                    }
                }
            }
            default -> throw new IllegalArgumentException("不支持的 PLM 认证类型");
        }
    }

    /** Reports a missing configuration field without echoing its value. */
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PLM 认证配置缺少 " + field);
        }
        return value;
    }

    /** JIT and password grant use POST regardless of the PIN provider's configurable method. */
    private static void requirePost(PlmAuthConfig auth) {
        if (!"POST".equalsIgnoreCase(auth.getMethod())) {
            throw new IllegalArgumentException("PLM 认证 method 必须为 POST");
        }
    }

    /** Allows examples to be saved but refuses to send placeholder identities or credentials. */
    private static String configuredCredential(String value, String field) {
        if (value == null || value.isBlank() || value.startsWith("REPLACE_WITH_")) {
            throw new IllegalStateException("PLM 认证缺少有效的 " + field);
        }
        return value;
    }

    /** Adds the selected environment's Authorization to every HTTP request, overriding request-level auth. */
    public static synchronized void apply(PreparedRequest request, Environment environment) {
        if (request == null || environment == null || environment.getAuth() == null) {
            return;
        }
        PlmAuthConfig auth = environment.getAuth();
        validate(auth);
        String accessToken = getToken(environment);
        setHeader(request, auth.getHeaderName(), auth.getHeaderPrefix() + accessToken);
        // A saved Digest setting must not challenge and replace environment-managed authorization.
        request.transportAuth = null;
        request.environmentAuthEnvironment = environment;
    }

    /** On one 401, discard the cached token and replace this request's header before a single resend. */
    public static synchronized boolean retryAfterUnauthorized(PreparedRequest request) {
        Environment environment = EnvironmentService.getActiveEnvironment();
        if (request == null || request.environmentAuthEnvironment != environment || environment == null
                || environment.getAuth() == null) {
            return false;
        }
        clear();
        PlmAuthConfig auth = environment.getAuth();
        setHeader(request, auth.getHeaderName(), auth.getHeaderPrefix() + getToken(environment));
        return true;
    }

    /** Clears the current token when an environment changes or its configuration is edited. */
    public static synchronized void clear() {
        token = null;
        expiresAt = null;
        authenticatedEnvironment = null;
    }

    /** Reports whether the active environment has an unexpired in-memory token, without exposing it. */
    public static synchronized boolean isAuthenticated() {
        return token != null && authenticatedEnvironment == EnvironmentService.getActiveEnvironment()
                && (expiresAt == null || Instant.now().isBefore(expiresAt));
    }

    /** Replaces stale request-level Authorization with the current environment credential. */
    private static void setHeader(PreparedRequest request, String name, String value) {
        if (request.headersList == null) {
            request.headersList = new ArrayList<>();
        }
        request.headersList.removeIf(header -> header != null && header.getKey() != null
                && (header.getKey().equalsIgnoreCase(name) || header.getKey().equalsIgnoreCase("Authorization")));
        request.headersList.add(new HttpHeader(true, name, value));
    }

    /** Reuses one valid token per environment; network and parser errors become safe UI messages. */
    private static String getToken(Environment environment) {
        if (token != null && authenticatedEnvironment == environment
                && (expiresAt == null || Instant.now().isBefore(expiresAt.minusSeconds(30)))) {
            return token;
        }
        clear();
        try {
            return authenticate(environment);
        } catch (IOException exception) {
            throw new IllegalStateException("PLM 认证请求失败，请检查服务地址和网络连接");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PLM 认证已中断");
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("PLM ")) {
                throw exception;
            }
            // JDK URI/header exceptions may echo the JIT query or identity; never forward them.
            throw new IllegalStateException("PLM 认证配置或响应无效");
        }
    }

    /** Performs the chosen PLM protocol and publishes a token only after JSON validation succeeds. */
    private static String authenticate(Environment environment) throws IOException, InterruptedException {
        PlmAuthConfig auth = environment.getAuth();
        String url = resolve(environment, auth.getTokenUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(60));
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        switch (auth.getType()) {
            case "jit-token" -> {
                configuredCredential(auth.getPrincipal(), "principal");
                url = query(url, "grant_type", auth.getGrantType());
                url = query(url, "client_id", auth.getClientId());
                url = query(url, "client_secret", auth.getClientSecret());
                builder.header(auth.getPrincipalHeader(), auth.getPrincipalPrefix() + auth.getPrincipal());
            }
            case "password-token" -> {
                configuredCredential(auth.getUsername(), "username");
                configuredCredential(auth.getPassword(), "password");
                String basic = Base64.getEncoder().encodeToString(
                        (auth.getClientId() + ":" + auth.getClientSecret()).getBytes(StandardCharsets.UTF_8));
                String boundary = "----EasyPostman" + UUID.randomUUID().toString().replace("-", "");
                builder.header("Authorization", "Basic " + basic)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary);
                String payload = multipartPart(boundary, "username", auth.getUsername())
                        + multipartPart(boundary, "password", auth.getPassword())
                        + multipartPart(boundary, "grant_type", auth.getGrantType())
                        + "--" + boundary + "--\r\n";
                body = HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8);
            }
            case "pin-token" -> {
                configuredCredential(auth.getPin(), "pin");
                String location = auth.getPinLocation().toLowerCase(Locale.ROOT);
                if ("query".equals(location)) {
                    url = query(url, auth.getPinName(), auth.getPin());
                } else if ("header".equals(location)) {
                    builder.header(auth.getPinName(), auth.getPin());
                } else if (!"body".equals(location)) {
                    throw new IllegalArgumentException("pinLocation 必须为 body/header/query");
                }
                if (auth.getHeaders() != null) {
                    auth.getHeaders().forEach((name, value) -> builder.header(name, resolve(environment, value)));
                }
                if (auth.getBodyTemplate() != null || "body".equals(location)) {
                    Object template = auth.getBodyTemplate() instanceof String json
                            ? JSONUtil.parse(json) : auth.getBodyTemplate();
                    Object payload = template == null
                            ? Map.of(auth.getPinName(), auth.getPin())
                            : replacePin(template, auth.getPin());
                    builder.header("Content-Type", "application/json");
                    body = HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(payload), StandardCharsets.UTF_8);
                }
            }
            default -> throw new IllegalArgumentException("不支持的 PLM 认证类型");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(builder.uri(URI.create(url))
                        .method(auth.getMethod().toUpperCase(Locale.ROOT), body).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("PLM 认证失败，HTTP " + response.statusCode());
        }
        try {
            var json = JSONUtil.parseObj(response.body());
            Object value = json;
            for (String segment : auth.getTokenJsonPath().split("\\.")) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException();
                }
                value = map.get(segment);
            }
            if (!(value instanceof String accessToken) || accessToken.isBlank()
                    || accessToken.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw new IllegalArgumentException();
            }
            Long lifetime = json.getLong("expires_in");
            if (lifetime != null && lifetime <= 0) {
                throw new IllegalArgumentException();
            }
            expiresAt = lifetime == null ? null : Instant.now().plusSeconds(lifetime);
            token = accessToken;
            authenticatedEnvironment = environment;
            return token;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("PLM Token 响应缺少有效的 " + auth.getTokenJsonPath());
        }
    }

    /** Emits one UTF-8 multipart text field with required CRLF separators. */
    private static String multipartPart(String boundary, String name, String value) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n";
    }

    /** Replaces PIN only in JSON string values, preserving JSON structure and escaping on serialization. */
    private static Object replacePin(Object template, String pin) {
        if (template instanceof Map<?, ?> map) {
            var result = new java.util.LinkedHashMap<String, Object>();
            map.forEach((key, value) -> result.put(String.valueOf(key), replacePin(value, pin)));
            return result;
        }
        if (template instanceof List<?> list) {
            return list.stream().map(value -> replacePin(value, pin)).toList();
        }
        if (template instanceof String text) {
            return text.replace("{{pin}}", pin);
        }
        return template;
    }

    /** Resolves environment URL/header variables and rejects leftover placeholders before HTTP I/O. */
    private static String resolve(Environment environment, String value) {
        String result = value;
        for (Map.Entry<String, String> entry : environment.getVariables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        if (result.contains("{{")) {
            throw new IllegalArgumentException("PLM 认证 URL 存在未配置的环境变量");
        }
        return result;
    }

    /** Percent-encodes JIT or PIN query fields without exposing them to the app's request logs. */
    private static String query(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
