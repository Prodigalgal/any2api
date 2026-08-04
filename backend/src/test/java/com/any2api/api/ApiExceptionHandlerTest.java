package com.any2api.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.OpenAiRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void keepsTypedAndFallbackErrorsOnTheSameOpenAiShape() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/models"));
        var typed = handler.openAiRequest(OpenAiRequestException.unsupported(
            "seed", "seed is unsupported").withAcceptedParameters(
                java.util.List.of("model", "messages", "temperature")), exchange);
        var fallback = handler.badRequest(
            new IllegalArgumentException("invalid tool choice"), exchange);
        var typedError = (Map<String, Object>) typed.get("error");
        var fallbackError = (Map<String, Object>) fallback.get("error");

        assertThat(typedError)
            .containsEntry("type", "unsupported_parameter")
            .containsEntry("param", "seed")
            .containsEntry("code", "unsupported_parameter")
            .containsEntry("retryable", false)
            .containsEntry("accepted_parameters",
                java.util.List.of("model", "messages", "temperature"));
        assertThat(fallbackError)
            .containsEntry("type", "invalid_request_error")
            .containsEntry("param", null)
            .containsEntry("code", "invalid_request_error");
    }
}
