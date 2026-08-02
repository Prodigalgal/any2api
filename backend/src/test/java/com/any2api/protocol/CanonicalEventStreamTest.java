package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class CanonicalEventStreamTest {
    private final CanonicalRequest request = new CanonicalRequest(
        "request-id", CanonicalRequest.Protocol.RESPONSES, "alpha", "model", true,
        List.of(JsonNodeFactory.instance.objectNode().put("role", "user")
            .put("content", "hello")),
        Map.of(), Map.of(), List.of(), Map.of(), JsonNodeFactory.instance.objectNode());

    @Test
    void acceptsACompleteOrderedEventStream() {
        Flux<CanonicalEvent> events = Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "resp-1"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 1, "hello"),
            new CanonicalEvent.Usage(1, "request-id", 2, 1, 1, 0),
            new CanonicalEvent.Completed(1, "request-id", 3, "stop"));

        StepVerifier.create(CanonicalEventStream.enforce(request, events))
            .expectNextCount(4)
            .verifyComplete();
    }

    @Test
    void rejectsPayloadBeforeStartAndMissingTerminalEvents() {
        StepVerifier.create(CanonicalEventStream.enforce(request, Flux.just(
                new CanonicalEvent.OutputTextDelta(1, "request-id", 0, "hello"))))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(CanonicalProtocolException.class)
                .hasMessageContaining("payload_before_response_started"))
            .verify();

        StepVerifier.create(CanonicalEventStream.enforce(request, Flux.just(
                new CanonicalEvent.ResponseStarted(1, "request-id", 0, "resp-1"))))
            .expectNextCount(1)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(CanonicalProtocolException.class)
                .hasMessageContaining("missing_terminal_event"))
            .verify();
    }

    @Test
    void rejectsToolArgumentsThatDoNotBelongToAStartedCall() {
        Flux<CanonicalEvent> events = Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "resp-1"),
            new CanonicalEvent.ToolArgumentsDelta(
                1, "request-id", 1, "call-missing", "{}"));

        StepVerifier.create(CanonicalEventStream.enforce(request, events))
            .expectNextCount(1)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(CanonicalProtocolException.class)
                .hasMessageContaining("tool_arguments_before_start"))
            .verify();
    }
}
