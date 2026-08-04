package com.any2api.provider;

import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

@Component
public final class ModelRuntimeGuard {
    private final ConcurrentHashMap<ModelKey, RuntimeEntry> entries = new ConcurrentHashMap<>();
    private final BulkheadConfig bulkheadConfig;
    private final CircuitBreakerConfig circuitConfig;
    private final MeterRegistry meters;

    public ModelRuntimeGuard(Any2ApiProperties properties, MeterRegistry meters) {
        var runtime = properties.getModelRuntime();
        this.bulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(runtime.getMaxConcurrentRequests())
            .maxWaitDuration(runtime.getMaxQueueWait())
            .build();
        this.circuitConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(runtime.getCircuitSlidingWindow())
            .minimumNumberOfCalls(runtime.getCircuitMinimumCalls())
            .failureRateThreshold(runtime.getCircuitFailureRateThreshold())
            .waitDurationInOpenState(runtime.getCircuitOpenDuration())
            .permittedNumberOfCallsInHalfOpenState(2)
            .build();
        this.meters = meters;
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        Function<Admission, Flux<CanonicalEvent>> work
    ) {
        var entry = entries.computeIfAbsent(
            new ModelKey(request.providerId(), request.model()), this::create);
        return Flux.usingWhen(
            acquire(entry),
            admission -> work.apply(admission)
                .doOnNext(admission::event)
                .doOnError(admission::error),
            Admission::complete,
            (admission, error) -> admission.error(error).complete(),
            admission -> admission.cancel().complete());
    }

    public Snapshot snapshot(String providerId, String modelId) {
        var entry = entries.get(new ModelKey(providerId, modelId));
        if (entry == null) return new Snapshot(0, 0, "CLOSED", 0, 0);
        return new Snapshot(
            entry.active.get(), entry.waiting.get(), entry.circuit.getState().name(),
            (long) entry.bulkheadRejected.count(), (long) entry.circuitRejected.count());
    }

    public boolean callable(String providerId, String modelId) {
        var entry = entries.get(new ModelKey(providerId, modelId));
        if (entry == null) return true;
        return !java.util.Set.of(CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN)
            .contains(entry.circuit.getState());
    }

    private Mono<Admission> acquire(RuntimeEntry entry) {
        return Mono.fromCallable(() -> {
            var queuedAt = System.nanoTime();
            entry.waiting.incrementAndGet();
            var circuitPermission = false;
            try {
                circuitPermission = entry.circuit.tryAcquirePermission();
                if (!circuitPermission) {
                    entry.circuitRejected.increment();
                    throw new ModelRuntimeRejectedException("circuit_open");
                }
                if (!entry.bulkhead.tryAcquirePermission()) {
                    entry.bulkheadRejected.increment();
                    entry.circuit.releasePermission();
                    circuitPermission = false;
                    throw new ModelRuntimeRejectedException("model_concurrency_exhausted");
                }
                entry.active.incrementAndGet();
                return new Admission(entry, elapsedMillis(queuedAt));
            } catch (RuntimeException error) {
                if (circuitPermission) entry.circuit.releasePermission();
                throw error;
            } finally {
                entry.waiting.decrementAndGet();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeEntry create(ModelKey key) {
        var metricName = key.providerId() + "/" + key.modelId();
        var entry = new RuntimeEntry(
            Bulkhead.of(metricName, bulkheadConfig),
            CircuitBreaker.of(metricName, circuitConfig),
            Counter.builder("any2api.model.queue.rejected")
                .tag("provider", key.providerId()).tag("model", key.modelId())
                .tag("reason", "bulkhead_full").register(meters),
            Counter.builder("any2api.model.queue.rejected")
                .tag("provider", key.providerId()).tag("model", key.modelId())
                .tag("reason", "circuit_open").register(meters));
        Gauge.builder("any2api.model.concurrent", entry.active, AtomicInteger::get)
            .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters);
        Gauge.builder("any2api.model.queue.depth", entry.waiting, AtomicInteger::get)
            .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters);
        Gauge.builder("any2api.model.circuit.state", entry,
                value -> circuitValue(value.circuit.getState()))
            .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters);
        return entry;
    }

    private static int circuitValue(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
            case METRICS_ONLY, DISABLED -> -1;
        };
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    public final class Admission {
        private final RuntimeEntry entry;
        private final long queueMs;
        private final long startedAt = System.nanoTime();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Admission(RuntimeEntry entry, long queueMs) {
            this.entry = entry;
            this.queueMs = queueMs;
        }

        public long queueMs() { return queueMs; }

        private void event(CanonicalEvent event) {
            if (event instanceof CanonicalEvent.Failed failed) failure.set(failed.errorType());
        }

        private Admission error(Throwable error) {
            failure.compareAndSet(null, error.getClass().getSimpleName());
            return this;
        }

        private Admission cancel() {
            cancelled.set(true);
            return this;
        }

        private Mono<Void> complete() {
            return Mono.fromRunnable(() -> {
                if (!finished.compareAndSet(false, true)) return;
                var duration = System.nanoTime() - startedAt;
                var error = failure.get();
                if (cancelled.get()) {
                    entry.circuit.releasePermission();
                } else if (error == null) {
                    entry.circuit.onSuccess(duration, TimeUnit.NANOSECONDS);
                } else {
                    entry.circuit.onError(duration, TimeUnit.NANOSECONDS,
                        new ModelExecutionFailure(error));
                }
                entry.active.decrementAndGet();
                entry.bulkhead.onComplete();
            });
        }
    }

    private record ModelKey(String providerId, String modelId) {}

    private record RuntimeEntry(
        Bulkhead bulkhead,
        CircuitBreaker circuit,
        Counter bulkheadRejected,
        Counter circuitRejected,
        AtomicInteger active,
        AtomicInteger waiting
    ) {
        private RuntimeEntry(
            Bulkhead bulkhead,
            CircuitBreaker circuit,
            Counter bulkheadRejected,
            Counter circuitRejected
        ) {
            this(bulkhead, circuit, bulkheadRejected, circuitRejected,
                new AtomicInteger(), new AtomicInteger());
        }
    }

    public static final class ModelRuntimeRejectedException extends RuntimeException {
        private final String reason;

        private ModelRuntimeRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }

    public record Snapshot(
        int concurrent,
        int queueDepth,
        String circuitState,
        long bulkheadRejections,
        long circuitRejections
    ) {}

    private static final class ModelExecutionFailure extends RuntimeException {
        private ModelExecutionFailure(String reason) { super(reason); }
    }
}
