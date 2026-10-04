package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class ContextLengthErrorContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsNonRetryableOpenAi400ForBothNonStreamingProtocols() {
        for (var protocol : CanonicalRequest.Protocol.values()) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses"));
            var request = request(protocol, false);
            new OpenAiResponseWriter(mapper).write(request, Flux.just(failure(protocol)), exchange).block();
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(400);
            var error = mapper.readTree(exchange.getResponse().getBodyAsString().block()).path("error");
            assertThat(error.path("type").asText()).isEqualTo("invalid_request_error");
            assertThat(error.path("code").asText()).isEqualTo("context_length_exceeded");
            assertThat(error.path("retryable").asBoolean()).isFalse();
            assertThat(error.path("param").asText()).isEqualTo(protocol == CanonicalRequest.Protocol.RESPONSES ? "input" : "messages");
        }
    }

    @Test
    void emitsOneFailureTerminalAfterTheStreamHasStarted() {
        for (var protocol : CanonicalRequest.Protocol.values()) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses"));
            var request = request(protocol, true);
            new OpenAiResponseWriter(mapper).write(request, Flux.just(
                new CanonicalEvent.ResponseStarted(1, "context", 0, "resp_context"), failure(protocol)), exchange).block();
            var body = exchange.getResponse().getBodyAsString().block();
            assertThat(body).contains("context_length_exceeded").doesNotContain("response.completed");
            if (protocol == CanonicalRequest.Protocol.RESPONSES) {
                assertThat(body.split("event: response.failed", -1)).hasSize(2);
            } else assertThat(body.split("\\[DONE\\]", -1)).hasSize(2);
        }
    }

    private CanonicalRequest request(CanonicalRequest.Protocol protocol, boolean stream) {
        return new CanonicalRequest("context", protocol, "mimo", "mimo-v2.6-pro", stream,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode().put("stream", stream));
    }

    private CanonicalEvent.Failed failure(CanonicalRequest.Protocol protocol) {
        return new CanonicalEvent.Failed(1, "context", 1, "context_length_exceeded", "Web input is too long",
            Map.of("retryable", false, "param", protocol == CanonicalRequest.Protocol.RESPONSES ? "input" : "messages"));
    }
}
