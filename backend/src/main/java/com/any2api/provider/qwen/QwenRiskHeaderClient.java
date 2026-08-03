package com.any2api.provider.qwen;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestCorrelation;
import java.util.LinkedHashMap;
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
}
