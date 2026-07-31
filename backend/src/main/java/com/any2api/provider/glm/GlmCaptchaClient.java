package com.any2api.provider.glm;

import com.any2api.config.Any2ApiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class GlmCaptchaClient {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String token;

    GlmCaptchaClient(
        WebClient.Builder builder,
        Any2ApiProperties properties,
        ObjectMapper mapper
    ) {
        this.client = builder.clone()
            .baseUrl(properties.getAutomation().getBaseUrl().toString())
            .build();
        this.mapper = mapper;
        this.token = properties.getSecurity().getInternalToken();
    }

    Mono<String> solve(String browserSessionId, int timeoutSeconds) {
        var body = mapper.createObjectNode().put("timeout_seconds", timeoutSeconds);
        return client.post()
            .uri(
                "/internal/v1/providers/glm/browser-sessions/{sessionId}/captcha",
                browserSessionId)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.setBearerAuth(token);
            })
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(result -> {
                var ticket = result.path("ticket").asText("").trim();
                if (ticket.length() < 40) {
                    throw new GlmUpstreamException(
                        502, "GLM captcha worker returned an incomplete ticket");
                }
                return ticket;
            });
    }
}
