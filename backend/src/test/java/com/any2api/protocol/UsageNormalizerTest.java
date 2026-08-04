package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.JsonNodeFactory;

class UsageNormalizerTest {
    private final UsageNormalizer normalizer = new UsageNormalizer();

    @Test
    void estimatesMissingUsageFromTheActualRequestAndGeneratedOutput() {
        var events = normalizer.normalize(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "response-id"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 1, "hello world"),
            new CanonicalEvent.Completed(1, "request-id", 2, "stop")))
            .collectList().block();

        assertThat(events).isNotNull();
        var usage = events.stream().filter(CanonicalEvent.Usage.class::isInstance)
            .map(CanonicalEvent.Usage.class::cast).findFirst().orElseThrow();
        assertThat(usage.source()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(usage.inputTokens()).isPositive();
        assertThat(usage.outputTokens()).isPositive();
        assertThat(events.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
    }

    @Test
    void replacesZeroUsageButPreservesValidUpstreamCounters() {
        var zero = normalizer.normalize(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "response-id"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 1, "ok"),
            new CanonicalEvent.Usage(1, "request-id", 2, 0, 0, 0),
            new CanonicalEvent.Completed(1, "request-id", 3, "stop")))
            .ofType(CanonicalEvent.Usage.class).single().block();
        var upstream = normalizer.normalize(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "response-id"),
            new CanonicalEvent.Usage(1, "request-id", 1, 7, 3, 1),
            new CanonicalEvent.Completed(1, "request-id", 2, "stop")))
            .ofType(CanonicalEvent.Usage.class).single().block();

        assertThat(zero).isNotNull();
        assertThat(zero.source()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(upstream).isNotNull();
        assertThat(upstream.source()).isEqualTo(UsageSource.UPSTREAM);
        assertThat(upstream.inputTokens()).isEqualTo(7);
        assertThat(upstream.outputTokens()).isEqualTo(3);
    }

    @Test
    void fillsPartialCountersAndMarksThemEstimated() {
        var usage = normalizer.normalize(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "response-id"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 1, "generated text"),
            new CanonicalEvent.Usage(1, "request-id", 2, 11, 0, 0),
            new CanonicalEvent.Completed(1, "request-id", 3, "stop")))
            .ofType(CanonicalEvent.Usage.class).single().block();

        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).isEqualTo(11);
        assertThat(usage.outputTokens()).isPositive();
        assertThat(usage.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void replacesImplausiblyInflatedFieldsButKeepsReasonableCounters() {
        var usage = normalizer.normalize(request(), Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "response-id"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 1, "OK."),
            new CanonicalEvent.Usage(1, "request-id", 2, 3102, 16, 0),
            new CanonicalEvent.Completed(1, "request-id", 3, "stop")))
            .ofType(CanonicalEvent.Usage.class).single().block();

        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).isLessThan(3102);
        assertThat(usage.outputTokens()).isEqualTo(16);
        assertThat(usage.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    private CanonicalRequest request() {
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user").put("content", "hello");
        var raw = JsonNodeFactory.instance.objectNode().put("model", "model");
        raw.putArray("messages").add(message);
        return new CanonicalRequest(
            "request-id", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "alpha", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);
    }
}
