package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class StrictToolArgumentsGuardTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buffersParallelArgumentsUntilTheyAllValidateAndPreservesEventOrder() {
        var upstream = Sinks.many().unicast().<CanonicalEvent>onBackpressureBuffer();
        var received = new ArrayList<CanonicalEvent>();
        var subscription = CanonicalEventStream.enforce(request(), upstream.asFlux()).subscribe(received::add);
        upstream.tryEmitNext(new CanonicalEvent.ResponseStarted(1, "r", 0, "resp"));
        upstream.tryEmitNext(new CanonicalEvent.ToolCallStarted(1, "r", 1, "a", "probe"));
        upstream.tryEmitNext(new CanonicalEvent.ToolArgumentsDelta(1, "r", 2, "a", "{\"count\":"));
        upstream.tryEmitNext(new CanonicalEvent.ToolCallStarted(1, "r", 3, "b", "probe"));
        upstream.tryEmitNext(new CanonicalEvent.ToolCallCompleted(1, "r", 4, "b", "{\"count\":2}"));
        assertThat(received).hasSize(1);
        upstream.tryEmitNext(new CanonicalEvent.ToolCallCompleted(1, "r", 5, "a", "{\"count\":1}"));
        upstream.tryEmitNext(new CanonicalEvent.Completed(1, "r", 6, "tool_calls"));
        upstream.tryEmitComplete();
        assertThat(received).extracting(CanonicalEvent::sequenceNumber).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        subscription.dispose();
    }

    @Test
    void emitsFailureWithoutExposingAnyInvalidToolEvents() {
        var events = CanonicalEventStream.enforce(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "r", 0, "resp"),
            new CanonicalEvent.ToolCallStarted(1, "r", 1, "a", "probe"),
            new CanonicalEvent.ToolArgumentsDelta(1, "r", 2, "a", "{\"count\":\"wrong\"}"),
            new CanonicalEvent.ToolCallCompleted(1, "r", 3, "a", "{\"count\":\"wrong\"}"),
            new CanonicalEvent.Completed(1, "r", 4, "tool_calls"))).collectList().block();
        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(CanonicalEvent.Failed.class,
            failed -> assertThat(failed.errorType()).isEqualTo("tool_call_generation_failed"));
        assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.ToolCallStarted
            || event instanceof CanonicalEvent.ToolArgumentsDelta || event instanceof CanonicalEvent.ToolCallCompleted);
    }

    @Test
    void rejectsConflictingFinalArgumentsAndDropsPendingEventsOnUpstreamFailure() {
        var started = new CanonicalEvent.ResponseStarted(1, "r", 0, "resp");
        var call = new CanonicalEvent.ToolCallStarted(1, "r", 1, "a", "probe");
        var delta = new CanonicalEvent.ToolArgumentsDelta(1, "r", 2, "a", "{\"count\":1}");
        var conflict = CanonicalEventStream.enforce(request(), Flux.just(started, call, delta,
            new CanonicalEvent.ToolCallCompleted(1, "r", 3, "a", "{\"count\":2}"))).collectList().block();
        assertThat(conflict).hasSize(2);
        assertThat(conflict.getLast()).isInstanceOf(CanonicalEvent.Failed.class);
        var failed = new CanonicalEvent.Failed(1, "r", 3, "upstream_timeout", "timeout", Map.of());
        assertThat(CanonicalEventStream.enforce(request(), Flux.just(started, call, delta, failed))
            .collectList().block()).containsExactly(started, failed);
    }

    @Test
    void keepsMissingTerminalAsAProtocolFailure() {
        StepVerifier.create(CanonicalEventStream.enforce(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "r", 0, "resp"),
            new CanonicalEvent.ToolCallStarted(1, "r", 1, "a", "probe"))))
            .expectNextCount(1).expectError(CanonicalProtocolException.class).verify();
    }

    private CanonicalRequest request() {
        var tool = mapper.readTree("""
            {"type":"function","name":"probe","strict":true,
             "parameters":{"type":"object","additionalProperties":false,
             "properties":{"count":{"type":"integer"}},"required":["count"]}}
            """);
        return new CanonicalRequest("r", CanonicalRequest.Protocol.RESPONSES, "mimo", "fixture", true,
            List.of(), Map.of(), Map.of(), List.of(tool), Map.of(), mapper.createObjectNode());
    }
}
