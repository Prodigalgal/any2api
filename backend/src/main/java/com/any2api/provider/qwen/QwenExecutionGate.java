package com.any2api.provider.qwen;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class QwenExecutionGate {
    private final Semaphore semaphore = new Semaphore(1, true);

    <T> Flux<T> flux(Supplier<Flux<T>> work) {
        return Flux.usingWhen(
            acquire(),
            ignored -> Flux.defer(work),
            Permit::release,
            (permit, ignored) -> permit.release(),
            Permit::release);
    }

    <T> Mono<T> mono(Supplier<Mono<T>> work) {
        return Mono.usingWhen(
            acquire(),
            ignored -> Mono.defer(work),
            Permit::release,
            (permit, ignored) -> permit.release(),
            Permit::release);
    }

    private Mono<Permit> acquire() {
        return Mono.fromCallable(() -> {
            semaphore.acquire();
            return new Permit(semaphore);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static final class Permit {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        private Mono<Void> release() {
            return Mono.fromRunnable(() -> {
                if (released.compareAndSet(false, true)) semaphore.release();
            });
        }
    }
}
