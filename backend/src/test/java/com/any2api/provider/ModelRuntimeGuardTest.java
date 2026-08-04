package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class ModelRuntimeGuardTest {
    @Test
    void opensThePerModelCircuitAfterTheConfiguredRollingFailures() {
        var properties = new Any2ApiProperties();
        properties.getModelRuntime().setCircuitMinimumCalls(2);
        properties.getModelRuntime().setCircuitSlidingWindow(2);
        properties.getModelRuntime().setCircuitFailureRateThreshold(50);
        properties.getModelRuntime().setCircuitOpenDuration(Duration.ofMinutes(1));
        var guard = new ModelRuntimeGuard(properties, new SimpleMeterRegistry());
        var request = request();

        for (var attempt = 0; attempt < 2; attempt++) {
            StepVerifier.create(guard.execute(request, ignored -> Flux.just(
                    new CanonicalEvent.Failed(
                        1, request.requestId(), 0, "rate_limited", "limited", Map.of()))))
                .expectNextCount(1)
                .verifyComplete();
        }

        assertThat(guard.snapshot("alpha", "model").circuitState()).isEqualTo("OPEN");
        StepVerifier.create(guard.execute(request, ignored -> Flux.empty()))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(ModelRuntimeGuard.ModelRuntimeRejectedException.class)
                .hasMessage("circuit_open"))
            .verify();
    }

    @Test
    void downstreamCancellationDoesNotCountAsAnUpstreamFailure() {
        var properties = new Any2ApiProperties();
        properties.getModelRuntime().setCircuitMinimumCalls(2);
        properties.getModelRuntime().setCircuitSlidingWindow(2);
        properties.getModelRuntime().setCircuitFailureRateThreshold(50);
        var guard = new ModelRuntimeGuard(properties, new SimpleMeterRegistry());
        var request = request();

        StepVerifier.create(guard.execute(request, ignored -> Flux.never()))
            .thenCancel()
            .verify();
        StepVerifier.create(guard.execute(request, ignored -> Flux.just(
                new CanonicalEvent.Failed(
                    1, request.requestId(), 0, "rate_limited", "limited", Map.of()))))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(guard.snapshot("alpha", "model").circuitState()).isEqualTo("CLOSED");
    }

    private CanonicalRequest request() {
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user").put("content", "hello");
        return new CanonicalRequest(
            "request-id", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "alpha", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), JsonNodeFactory.instance.objectNode());
    }
}
