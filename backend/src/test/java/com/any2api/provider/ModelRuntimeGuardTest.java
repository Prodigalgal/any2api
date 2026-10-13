package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class ModelRuntimeGuardTest {
    @Test
    void pastFailuresDoNotPreventAnotherRealRequest() {
        var guard = guard();
        for (var attempt = 0; attempt < 25; attempt++) {
            StepVerifier.create(guard.execute(request(), ignored -> Flux.just(
                    new CanonicalEvent.Failed(1, "request-id", 0, "rate_limited", "limited", Map.of()))))
                .expectNextCount(1).verifyComplete();
        }
        StepVerifier.create(guard.execute(request(), ignored -> Flux.just(
                new CanonicalEvent.Completed(1, "request-id", 0, "stop"))))
            .expectNextCount(1).verifyComplete();
        assertThat(guard.snapshot("alpha", "model").concurrent()).isZero();
        assertThat(guard.snapshot("alpha", "model").bulkheadRejections()).isZero();
    }

    @Test
    void errorsReleaseTheSlotAndKeepTheirOriginalCause() {
        var guard = guard();
        var failure = new IllegalStateException("failure");
        StepVerifier.create(guard.execute(request(), ignored -> Flux.error(failure)))
            .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();
        assertThat(guard.snapshot("alpha", "model").concurrent()).isZero();
        StepVerifier.create(guard.execute(request(), ignored -> Flux.empty())).verifyComplete();
    }

    @Test
    void concurrencyLimitRejectsExcessWorkAndCancellationReleasesTheSlot() throws Exception {
        var guard = guard();
        var acquired = new CountDownLatch(1);
        var first = guard.execute(request(), ignored -> Flux.<CanonicalEvent>never()
            .doOnSubscribe(subscription -> acquired.countDown())).subscribe();
        try {
            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(guard.snapshot("alpha", "model").concurrent()).isEqualTo(1);
            StepVerifier.create(guard.execute(request(), ignored -> Flux.empty()))
                .expectErrorSatisfies(error -> assertThat(error)
                    .isInstanceOf(ModelRuntimeGuard.ModelRuntimeRejectedException.class)
                    .hasMessage("model_concurrency_exhausted"))
                .verify();
            assertThat(guard.snapshot("alpha", "model").bulkheadRejections()).isEqualTo(1);
        } finally {
            first.dispose();
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(guard.snapshot("alpha", "model").concurrent()).isZero());
        StepVerifier.create(guard.execute(request(), ignored -> Flux.empty())).verifyComplete();
    }

    private ModelRuntimeGuard guard() {
        var properties = new Any2ApiProperties();
        properties.getModelRuntime().setMaxConcurrentRequests(1);
        properties.getModelRuntime().setMaxQueueWait(Duration.ofMillis(25));
        return new ModelRuntimeGuard(properties, new SimpleMeterRegistry());
    }

    private CanonicalRequest request() {
        var message = JsonNodeFactory.instance.objectNode().put("role", "user").put("content", "hello");
        return new CanonicalRequest("request-id", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "alpha", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), JsonNodeFactory.instance.objectNode());
    }
}
