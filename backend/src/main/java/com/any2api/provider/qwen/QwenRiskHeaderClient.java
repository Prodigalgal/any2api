package com.any2api.provider.qwen;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class QwenRiskHeaderClient {
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
        "bx-ua", "bx-umidtoken", "bx-v", "version", "user-agent",
        "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform");
    private final WebClient client;
    private final String token;

    QwenRiskHeaderClient(WebClient.Builder builder, Any2ApiProperties properties) {
        this.client = builder.clone()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(20 << 20))
            .filter(RequestCorrelation.propagationFilter())
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        this.token = properties.getSecurity().getInternalToken();
    }

    Mono<Map<String, String>> generate(String url, String method, String body) {
        return client.post()
            .uri("/internal/v1/providers/qwen/risk-headers")
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            })
            .bodyValue(Map.of("url", url, "method", method, "body", body))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(response -> {
                var result = new LinkedHashMap<String, String>();
                response.path("headers").properties().forEach(entry -> {
                    var key = entry.getKey().toLowerCase();
                    if (ALLOWED.contains(key) && entry.getValue().isTextual()) {
                        result.put(key, entry.getValue().asText());
                    }
                });
                if (!result.containsKey("bx-v") || !result.containsKey("version")) {
                    throw new IllegalStateException("Qwen risk service omitted bx-v or version");
                }
                return Map.copyOf(result);
            });
    }

    Mono<BrowserResponse> browserFetch(
        String method,
        String path,
        String body,
        String bearerToken,
        String accountId,
        Map<String, String> cookies,
        JsonNode browserState,
        JsonNode browserFingerprint,
        String transportSessionId,
        String refererPath,
        int timeoutSeconds
    ) {
        var request = new LinkedHashMap<String, Object>();
        request.put("method", method);
        request.put("path", path);
        request.put("body", body == null ? "" : body);
        request.put("bearer_token", bearerToken);
        request.put("account_id", accountId);
        request.put("cookies", cookies);
        request.put("browser_state", browserState);
        request.put("browser_fingerprint", browserFingerprint);
        request.put("transport_session_id", transportSessionId);
        request.put("referer_path", refererPath);
        request.put("timeout_seconds", timeoutSeconds);
        return client.post()
            .uri("/internal/v1/providers/qwen/browser-fetch")
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            })
            .bodyValue(request)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(response -> {
                try {
                    return new BrowserResponse(
                        response.path("status").asInt(502),
                        response.path("content_type").asText("application/octet-stream"),
                        Base64.getDecoder().decode(response.path("body_base64").asText("")),
                        response.path("credential_patch"),
                        response.path("transport_mode").asText("native_browser_buffered"));
                } catch (IllegalArgumentException error) {
                    throw new IllegalStateException(
                        "Qwen browser service returned invalid response bytes", error);
                }
            });
    }

    record BrowserResponse(
        int status,
        String contentType,
        byte[] body,
        JsonNode credentialPatch,
        String transportMode
    ) {
        BrowserResponse {
            body = body.clone();
            credentialPatch = credentialPatch.deepCopy();
        }
        @Override public JsonNode credentialPatch() { return credentialPatch.deepCopy(); }
        boolean successful() { return status >= 200 && status < 300; }
        String text() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    }

}
