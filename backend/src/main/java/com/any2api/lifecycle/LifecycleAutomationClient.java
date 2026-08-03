package com.any2api.lifecycle;

import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.OperationContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class LifecycleAutomationClient {
    private final WebClient client;
    private final String token;
    private final ObjectMapper mapper;

    LifecycleAutomationClient(
        WebClient.Builder builder,
        Any2ApiProperties properties,
        ObjectMapper mapper
    ) {
        client = builder.baseUrl(properties.getAutomation().getBaseUrl().toString()).build();
        token = properties.getSecurity().getInternalToken();
        this.mapper = mapper;
    }

    Mono<JsonNode> execute(String providerId, String operation, JsonNode credential) {
        return execute(providerId, operation, Map.of("credential", credential));
    }

    Mono<JsonNode> execute(String providerId, String operation, Map<String, ?> payload) {
        var correlationId = UUID.randomUUID().toString();
        return execute(providerId, operation, payload,
            new OperationContext(correlationId, "AUTOMATION", correlationId, 1));
    }

    Mono<JsonNode> execute(
        String providerId,
        String operation,
        Map<String, ?> payload,
        OperationContext context
    ) {
        return client.post()
            .uri("/internal/v1/providers/{provider}/execute", providerId)
            .headers(headers -> {
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (!token.isBlank()) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                headers.set("X-Any2API-Correlation-Id", context.correlationId());
            })
            .bodyValue(Map.of(
                "operation", operation,
                "payload", payload,
                "context", context.toWire()))
            .exchangeToMono(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        try {
                            return Mono.just(mapper.readTree(body));
                        } catch (RuntimeException error) {
                            return Mono.error(new AutomationInvocationException(
                                context.correlationId(), response.statusCode().value(),
                                "invalid_automation_response", "automation_response",
                                "automation returned invalid JSON", error));
                        }
                    }
                    var details = errorDetails(body, response.statusCode().value());
                    return Mono.error(new AutomationInvocationException(
                        context.correlationId(), response.statusCode().value(),
                        details.code(), details.stage(), details.message(), null));
                }))
            .onErrorMap(error -> error instanceof AutomationInvocationException
                ? error
                : new AutomationInvocationException(
                    context.correlationId(), 0, "automation_transport_error", "transport",
                    "automation transport failed (" + error.getClass().getSimpleName() + ")",
                    error));
    }

    private ErrorDetails errorDetails(String body, int status) {
        try {
            var error = mapper.readTree(body).path("detail").path("error");
            if (error.isObject()) {
                return new ErrorDetails(
                    error.path("code").asText("automation_http_" + status),
                    error.path("stage").asText("automation"),
                    error.path("message").asText("automation operation failed"));
            }
        } catch (RuntimeException ignored) {
            // Unstructured upstream bodies are deliberately not persisted.
        }
        return new ErrorDetails(
            "automation_http_" + status, "automation",
            "automation operation returned HTTP " + status);
    }

    private record ErrorDetails(String code, String stage, String message) {}
}
