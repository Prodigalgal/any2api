package com.any2api.protocol;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

public final class CanonicalResponseGuard {
    private CanonicalResponseGuard() {}

    public static Flux<CanonicalEvent> holdUntilMeaningfulOutput(
        CanonicalRequest request,
        Flux<CanonicalEvent> events
    ) {
        return Flux.defer(() -> {
            var buffered = new ArrayList<CanonicalEvent>();
            var released = new AtomicBoolean();
            return events.concatMap(event -> {
                if (released.get()) return Flux.just(event);
                if (event instanceof CanonicalEvent.Failed) {
                    buffered.clear();
                    released.set(true);
                    return Flux.just(event);
                }
                buffered.add(event);
                if (meaningful(event)) {
                    released.set(true);
                    var ready = java.util.List.copyOf(buffered);
                    buffered.clear();
                    return Flux.fromIterable(ready);
                }
                if (event instanceof CanonicalEvent.Completed completed) {
                    buffered.clear();
                    released.set(true);
                    return Flux.just(new CanonicalEvent.Failed(
                        completed.schemaVersion(), request.requestId(),
                        completed.sequenceNumber(), "empty_model_response",
                        "provider returned no model output", Map.of()));
                }
                return Flux.empty();
            });
        });
    }

    private static boolean meaningful(CanonicalEvent event) {
        return event instanceof CanonicalEvent.OutputTextDelta text && !text.delta().isBlank()
            || event instanceof CanonicalEvent.ReasoningDelta reasoning
                && !reasoning.delta().isBlank()
            || event instanceof CanonicalEvent.ToolCallStarted;
    }
}
