package com.any2api.transport;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class BrowserTransportClient {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String token;

    public BrowserTransportClient(
        WebClient.Builder builder,
        Any2ApiProperties properties,
        ObjectMapper mapper
    ) {
        client = builder.clone()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(20 << 20))
            .filter(RequestCorrelation.propagationFilter())
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        token = properties.getSecurity().getInternalToken();
        this.mapper = mapper;
    }

    public Mono<Session> open(OpenCommand command) {
        var body = mapper.createObjectNode()
            .put("origin", command.origin().toString())
            .put("user_agent", value(command.userAgent()))
            .put("browser_profile", value(command.browserProfile(), "chrome136"))
            .put("http_version", value(command.httpVersion(), "v2"))
            .put("dynamic_proxy", false)
            .put("proxy_affinity_key", value(command.proxyAffinityKey()))
            .put("strict_proxy_affinity", command.strictProxyAffinity())
            .put("clearance_revision", value(command.clearanceRevision()))
            .put("bearer_token", value(command.bearerToken()))
            .put("ttl_seconds", command.ttlSeconds());
        body.set("cookies", mapper.valueToTree(command.cookies()));
        body.set("cookie_domains", mapper.valueToTree(command.cookieDomains()));
        body.set("origins", mapper.valueToTree(command.origins()));
        if (command.proxyPool() != null && !command.proxyPool().isEmpty()) {
            body.set("proxy_pool", mapper.valueToTree(command.proxyPool()));
        }
        return post("/internal/v1/browser-sessions", body)
            .bodyToMono(JsonNode.class)
            .map(result -> new Session(
                required(result, "session_id"),
                required(result, "user_agent"),
                required(result, "browser_profile"),
                required(result, "binding_id")));
    }

    public Mono<BufferedResponse> request(String sessionId, Request command) {
        return post("/internal/v1/browser-sessions/{sessionId}/request", requestBody(command), sessionId)
            .bodyToMono(JsonNode.class)
            .map(result -> {
                var encoded = required(result, "body_base64");
                byte[] body;
                try {
                    body = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException error) {
                    throw new BrowserTransportException(502,
                        "browser transport returned an invalid response body");
                }
                return new BufferedResponse(
                    result.path("status").asInt(502),
                    result.path("content_type").asText("application/octet-stream"),
                    body);
            });
    }

    public Flux<byte[]> stream(String sessionId, Request command) {
        return postRequest(
            "/internal/v1/browser-sessions/{sessionId}/stream", requestBody(command), sessionId)
            .exchangeToFlux(response -> {
                var status = response.statusCode().value();
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .flatMapMany(body -> Flux.error(new BrowserTransportException(
                            status, summarize(status, body))));
                }
                return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                    .map(buffer -> {
                        var bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    });
            });
    }

    public Mono<CloseResult> close(String sessionId) {
        return client.delete()
            .uri("/internal/v1/browser-sessions/{sessionId}", sessionId)
            .headers(headers -> {
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(result -> new CloseResult(result.path("context_patch")))
            .defaultIfEmpty(new CloseResult(
                tools.jackson.databind.node.MissingNode.getInstance()))
            .onErrorReturn(new CloseResult(
                tools.jackson.databind.node.MissingNode.getInstance()));
    }

    public Mono<JsonNode> refreshClearance(String sessionId, String path) {
        var body = mapper.createObjectNode()
            .put("path", path)
            .put("timeout_seconds", 120);
        return post(
                "/internal/v1/browser-sessions/{sessionId}/clearance/refresh",
                body, sessionId)
            .bodyToMono(JsonNode.class)
            .map(result -> requiredObject(result, "context_patch"));
    }

    public Mono<Void> applyClearance(String sessionId, JsonNode context) {
        if (context == null || !context.isObject()) {
            return Mono.error(new IllegalArgumentException(
                "browser clearance context must be an object"));
        }
        return client.put()
            .uri("/internal/v1/browser-sessions/{sessionId}/clearance", sessionId)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .bodyValue(context)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .then();
    }

    public Mono<WebSocketHandle> openWebSocket(
        String sessionId,
        String path,
        URI origin,
        int timeoutSeconds
    ) {
        return openWebSocket(
            sessionId, path, origin, timeoutSeconds, WebSocketMode.SESSION);
    }

    public Mono<WebSocketHandle> openWebSocket(
        String sessionId,
        String path,
        URI origin,
        int timeoutSeconds,
        WebSocketMode mode
    ) {
        var body = mapper.createObjectNode()
            .put("path", path)
            .put("origin", origin == null ? "" : origin.toString())
            .put("timeout_seconds", timeoutSeconds)
            .put("transport_mode", mode.externalName());
        return post("/internal/v1/browser-sessions/{sessionId}/websockets", body, sessionId)
            .bodyToMono(JsonNode.class)
            .map(result -> new WebSocketHandle(required(result, "websocket_id")));
    }

    public Mono<Void> sendWebSocket(
        String sessionId,
        String websocketId,
        JsonNode body
    ) {
        var request = mapper.createObjectNode();
        request.set("json_body", body);
        return post(
                "/internal/v1/browser-sessions/{sessionId}/websockets/{websocketId}/send",
                request, sessionId, websocketId)
            .bodyToMono(JsonNode.class)
            .then();
    }

    public Mono<WebSocketFrame> receiveWebSocket(String sessionId, String websocketId) {
        return post(
                "/internal/v1/browser-sessions/{sessionId}/websockets/{websocketId}/receive",
                mapper.createObjectNode(), sessionId, websocketId)
            .bodyToMono(JsonNode.class)
            .map(result -> {
                byte[] body;
                try {
                    body = Base64.getDecoder().decode(required(result, "body_base64"));
                } catch (IllegalArgumentException error) {
                    throw new BrowserTransportException(502,
                        "browser transport returned an invalid WebSocket frame");
                }
                return new WebSocketFrame(body, result.path("flags").asInt(0));
            });
    }

    public Mono<Void> closeWebSocket(String sessionId, String websocketId) {
        return client.delete()
            .uri(
                "/internal/v1/browser-sessions/{sessionId}/websockets/{websocketId}",
                sessionId, websocketId)
            .headers(headers -> {
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .retrieve()
            .bodyToMono(JsonNode.class)
            .then()
            .onErrorResume(ignored -> Mono.empty());
    }

    private WebClient.ResponseSpec post(String path, JsonNode body, Object... variables) {
        return postRequest(path, body, variables).retrieve()
            .onStatus(status -> !status.is2xxSuccessful(), response -> {
                var status = response.statusCode().value();
                return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                    .map(value -> new BrowserTransportException(
                        status, summarize(status, value)));
            });
    }

    private WebClient.RequestHeadersSpec<?> postRequest(
        String path,
        JsonNode body,
        Object... variables
    ) {
        return client.post().uri(path, variables)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .bodyValue(body);
    }

    private ObjectNode requestBody(Request command) {
        var body = mapper.createObjectNode()
            .put("method", command.method())
            .put("path", command.path())
            .put("fingerprint_profile", command.fingerprintProfile().externalName())
            .put("timeout_seconds", command.timeoutSeconds());
        body.set("headers", mapper.valueToTree(command.headers()));
        if (command.body() != null && !command.body().isNull()) {
            body.set("json_body", command.body());
        }
        if (command.rawBody() != null && command.rawBody().length > 0) {
            body.put("body_base64", Base64.getEncoder().encodeToString(command.rawBody()));
        }
        if (command.origin() != null) body.put("origin", command.origin().toString());
        body.put("referer_path", value(command.refererPath(), "/"));
        return body;
    }

    private static String required(JsonNode source, String field) {
        var value = source.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new BrowserTransportException(502,
                "browser transport response is missing " + field);
        }
        return value;
    }

    private static JsonNode requiredObject(JsonNode source, String field) {
        var value = source.path(field);
        if (!value.isObject() || value.isEmpty()) {
            throw new BrowserTransportException(502,
                "browser transport response is missing " + field);
        }
        return value.deepCopy();
    }

    private static String summarize(int status, byte[] body) {
        var compact = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "browser transport returned HTTP " + status
            : "browser transport returned HTTP " + status + ": " + compact;
    }

    private static String value(String input) { return input == null ? "" : input.trim(); }
    private static String value(String input, String fallback) {
        var normalized = value(input);
        return normalized.isBlank() ? fallback : normalized;
    }

    public record OpenCommand(
        URI origin,
        Map<String, String> cookies,
        List<String> cookieDomains,
        String userAgent,
        String browserProfile,
        String httpVersion,
        Map<String, Object> proxyPool,
        int ttlSeconds,
        List<URI> origins,
        String proxyAffinityKey,
        boolean strictProxyAffinity,
        String clearanceRevision,
        String bearerToken
    ) {
        public OpenCommand(
            URI origin,
            Map<String, String> cookies,
            List<String> cookieDomains,
            String userAgent,
            String browserProfile,
            String httpVersion,
            Map<String, Object> proxyPool,
            int ttlSeconds
        ) {
            this(origin, cookies, cookieDomains, userAgent, browserProfile, httpVersion,
                proxyPool, ttlSeconds, List.of(), "", false, "", "");
        }

        public OpenCommand(
            URI origin,
            Map<String, String> cookies,
            List<String> cookieDomains,
            String userAgent,
            String browserProfile,
            String httpVersion,
            Map<String, Object> proxyPool,
            int ttlSeconds,
            List<URI> origins
        ) {
            this(origin, cookies, cookieDomains, userAgent, browserProfile, httpVersion,
                proxyPool, ttlSeconds, origins, "", false, "", "");
        }

        public OpenCommand(
            URI origin,
            Map<String, String> cookies,
            List<String> cookieDomains,
            String userAgent,
            String browserProfile,
            String httpVersion,
            Map<String, Object> proxyPool,
            int ttlSeconds,
            List<URI> origins,
            String proxyAffinityKey,
            boolean strictProxyAffinity,
            String clearanceRevision
        ) {
            this(origin, cookies, cookieDomains, userAgent, browserProfile, httpVersion,
                proxyPool, ttlSeconds, origins, proxyAffinityKey, strictProxyAffinity,
                clearanceRevision, "");
        }

        public OpenCommand {
            origins = origins == null ? List.of() : List.copyOf(origins);
            proxyAffinityKey = value(proxyAffinityKey);
            clearanceRevision = value(clearanceRevision);
            bearerToken = value(bearerToken);
        }
    }

    public record Session(
        String id,
        String userAgent,
        String browserProfile,
        String bindingId
    ) {
        public Session(String id, String userAgent, String browserProfile) {
            this(id, userAgent, browserProfile, "");
        }
    }

    public record CloseResult(JsonNode contextPatch) {}

    public record WebSocketHandle(String id) {}

    public enum WebSocketMode {
        SESSION("session"),
        BROWSER("browser");

        private final String externalName;
        WebSocketMode(String externalName) { this.externalName = externalName; }
        public String externalName() { return externalName; }
    }

    public record WebSocketFrame(byte[] body, int flags) {
        public WebSocketFrame { body = body.clone(); }
    }

    public record Request(
        String method,
        String path,
        Map<String, String> headers,
        FingerprintProfile fingerprintProfile,
        JsonNode body,
        int timeoutSeconds,
        URI origin,
        byte[] rawBody,
        String refererPath
    ) {
        public Request(
            String method,
            String path,
            Map<String, String> headers,
            FingerprintProfile fingerprintProfile,
            JsonNode body,
            int timeoutSeconds
        ) {
            this(method, path, headers, fingerprintProfile, body, timeoutSeconds,
                null, null, "/");
        }

        public Request(
            String method,
            String path,
            Map<String, String> headers,
            FingerprintProfile fingerprintProfile,
            JsonNode body,
            int timeoutSeconds,
            URI origin
        ) {
            this(method, path, headers, fingerprintProfile, body, timeoutSeconds,
                origin, null, "/");
        }

        public static Request binary(
            String method,
            String path,
            Map<String, String> headers,
            FingerprintProfile fingerprintProfile,
            byte[] body,
            int timeoutSeconds,
            URI origin,
            String refererPath
        ) {
            return new Request(method, path, headers, fingerprintProfile, null,
                timeoutSeconds, origin, body, refererPath);
        }

        public Request {
            rawBody = rawBody == null ? null : rawBody.clone();
        }
    }

    public enum FingerprintProfile {
        NONE("none"),
        NAVIGATION("navigation"),
        SAME_ORIGIN_FETCH("same_origin_fetch");

        private final String externalName;

        FingerprintProfile(String externalName) { this.externalName = externalName; }
        public String externalName() { return externalName; }
    }

    public record BufferedResponse(int status, String contentType, byte[] body) {
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public boolean successful() { return status >= 200 && status < 300; }
    }

    public static final class BrowserTransportException extends RuntimeException {
        private final int status;

        public BrowserTransportException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }
}
