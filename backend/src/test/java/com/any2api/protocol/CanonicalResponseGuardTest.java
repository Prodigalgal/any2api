package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.JsonNodeFactory;

class CanonicalResponseGuardTest {
    @Test
    void convertsAStartedButEmptyResponseToAPreOutputFailure() {
        var request = request();
        var events = CanonicalResponseGuard.holdUntilMeaningfulOutput(request, Flux.just(
            new CanonicalEvent.ResponseStarted(1, request.requestId(), 0, "response-id"),
            new CanonicalEvent.Usage(1, request.requestId(), 1, 10, 0, 0),
            new CanonicalEvent.Completed(1, request.requestId(), 2, "stop")))
            .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOfSatisfying(
            CanonicalEvent.Failed.class,
            failure -> assertThat(failure.errorType()).isEqualTo("empty_model_response"));
    }

    @Test
    void releasesTheBufferedPrefixAsSoonAsOutputIsMeaningful() {
        var request = request();
        var events = CanonicalResponseGuard.holdUntilMeaningfulOutput(request, Flux.just(
            new CanonicalEvent.ResponseStarted(1, request.requestId(), 0, "response-id"),
            new CanonicalEvent.OutputTextDelta(1, request.requestId(), 1, "hello"),
            new CanonicalEvent.Completed(1, request.requestId(), 2, "stop")))
            .collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.getFirst()).isInstanceOf(CanonicalEvent.ResponseStarted.class);
        assertThat(events.get(1)).isInstanceOf(CanonicalEvent.OutputTextDelta.class);
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
    }

    private CanonicalRequest request() {
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user").put("content", "hello");
        return new CanonicalRequest(
            "request-id", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "alpha", "model", true, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), JsonNodeFactory.instance.objectNode());
    }
}
