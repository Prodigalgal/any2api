package com.any2api.lifecycle;

import com.any2api.config.Any2ApiProperties;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class LifecycleAutomationClient {
    private final WebClient client;
    private final String token;

    LifecycleAutomationClient(WebClient.Builder builder, Any2ApiProperties properties) {
        client = builder.baseUrl(properties.getAutomation().getBaseUrl().toString()).build();
        token = properties.getSecurity().getInternalToken();
    }

    Mono<JsonNode> execute(String providerId, String operation, JsonNode credential) {
        return execute(providerId, operation, Map.of("credential", credential));
    }

    Mono<JsonNode> execute(String providerId, String operation, Map<String, ?> payload) {
        return client.post()
            .uri("/internal/v1/providers/{provider}/execute", providerId)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            })
            .bodyValue(Map.of(
                "operation", operation,
                "payload", payload))
            .retrieve()
            .bodyToMono(JsonNode.class);
    }
}
