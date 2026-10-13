package com.any2api.provider;

import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public final class ModelRuntimeGuard {
    private final ConcurrentHashMap<ModelKey, RuntimeEntry> entries = new ConcurrentHashMap<>();
    private final BulkheadConfig bulkheadConfig;
    private final MeterRegistry meters;

    public ModelRuntimeGuard(Any2ApiProperties properties, MeterRegistry meters) {
        var runtime = properties.getModelRuntime();
        this.bulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(runtime.getMaxConcurrentRequests())
            .maxWaitDuration(runtime.getMaxQueueWait())
            .build();
        this.meters = meters;
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request,
        Function<Admission, Flux<CanonicalEvent>> work) {
        var entry = entries.computeIfAbsent(new ModelKey(request.providerId(), request.model()), this::create);
        return Flux.usingWhen(acquire(entry), work,
            Admission::complete, (admission, error) -> admission.complete(), Admission::complete)
            .doOnDiscard(Admission.class, Admission::release);
    }

    public Snapshot snapshot(String providerId, String modelId) {
        var entry = entries.get(new ModelKey(providerId, modelId));
        return entry == null ? new Snapshot(0, 0, 0)
            : new Snapshot(entry.active.get(), entry.waiting.get(), (long) entry.rejected.count());
    }

    private Mono<Admission> acquire(RuntimeEntry entry) {
        return Mono.fromCallable(() -> {
            var queuedAt = System.nanoTime();
            entry.waiting.incrementAndGet();
            try {
                if (!entry.bulkhead.tryAcquirePermission()) {
                    entry.rejected.increment();
                    throw new ModelRuntimeRejectedException("model_concurrency_exhausted");
                }
                entry.active.incrementAndGet();
                return new Admission(entry, Math.max(0,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt)));
            } finally {
                entry.waiting.decrementAndGet();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeEntry create(ModelKey key) {
        var entry = new RuntimeEntry(Bulkhead.of(key.providerId() + "/" + key.modelId(), bulkheadConfig),
            Counter.builder("any2api.model.queue.rejected")
                .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters),
            new AtomicInteger(), new AtomicInteger());
        Gauge.builder("any2api.model.concurrent", entry.active, AtomicInteger::get)
            .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters);
        Gauge.builder("any2api.model.queue.depth", entry.waiting, AtomicInteger::get)
            .tag("provider", key.providerId()).tag("model", key.modelId()).register(meters);
        return entry;
    }

    public final class Admission {
        private final RuntimeEntry entry;
        private final long queueMs;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Admission(RuntimeEntry entry, long queueMs) {
            this.entry = entry;
            this.queueMs = queueMs;
        }

        public long queueMs() { return queueMs; }

        private Mono<Void> complete() {
            return Mono.fromRunnable(this::release);
        }

        private void release() {
            if (!finished.compareAndSet(false, true)) return;
            entry.active.decrementAndGet();
            entry.bulkhead.onComplete();
        }
    }

    private record ModelKey(String providerId, String modelId) {}
    private record RuntimeEntry(Bulkhead bulkhead, Counter rejected, AtomicInteger active, AtomicInteger waiting) {}
    public record Snapshot(int concurrent, int queueDepth, long bulkheadRejections) {}

    public static final class ModelRuntimeRejectedException extends RuntimeException {
        private final String reason;
        private ModelRuntimeRejectedException(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
