package com.any2api.protocol;

import com.networknt.schema.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/** Holds strict tool events until complete arguments satisfy the caller's schema. */
final class StrictToolArgumentsGuard {
    private static final int MAX_BUFFERED_EVENTS = 4096;
    private static final int MAX_BUFFERED_CHARACTERS = 2 << 20;

    private StrictToolArgumentsGuard() {}

    static Flux<CanonicalEvent> enforce(CanonicalRequest request, Flux<CanonicalEvent> source) {
        var schemas = new HashMap<String, Schema>();
        for (var tool : request.tools()) {
            var definition = tool.path("function").isObject() ? tool.path("function") : tool;
            var schema = StrictFunctionSchema.compile(definition);
            if (schema != null) schemas.put(definition.path("name").asText(), schema);
        }
        if (schemas.isEmpty()) return source;
        return Flux.defer(() -> {
            var state = new State(schemas);
            return source.concatMapIterable(state::accept)
                .takeUntil(event -> event instanceof CanonicalEvent.Failed);
        });
    }

    private static final class State {
        private final Map<String, Schema> schemas;
        private final Map<String, PendingCall> pending = new HashMap<>();
        private final List<CanonicalEvent> buffered = new ArrayList<>();
        private int bufferedCharacters;

        private State(Map<String, Schema> schemas) { this.schemas = schemas; }

        private List<CanonicalEvent> accept(CanonicalEvent event) {
            if (event instanceof CanonicalEvent.Failed) {
                clear();
                return List.of(event);
            }
            if (event instanceof CanonicalEvent.ToolCallStarted started && schemas.containsKey(started.name())) {
                pending.put(started.toolCallId(), new PendingCall(schemas.get(started.name()), new StringBuilder()));
            }
            if (event instanceof CanonicalEvent.ToolArgumentsDelta delta && pending.containsKey(delta.toolCallId())) {
                var call = pending.get(delta.toolCallId());
                call.arguments().append(delta.delta());
                if (call.arguments().length() > StrictFunctionSchema.MAX_ARGUMENT_BYTES) return failure(event);
            }
            if (event instanceof CanonicalEvent.ToolCallCompleted completed && pending.containsKey(completed.toolCallId())) {
                var call = pending.remove(completed.toolCallId());
                if (completed.arguments() == null || !completed.arguments().startsWith(call.arguments().toString())
                    || !StrictFunctionSchema.accepts(call.schema(), completed.arguments())) return failure(event);
            }
            if (!buffered.isEmpty() || !pending.isEmpty()) {
                buffered.add(event);
                bufferedCharacters += payloadSize(event);
                if (buffered.size() > MAX_BUFFERED_EVENTS || bufferedCharacters > MAX_BUFFERED_CHARACTERS) return failure(event);
                if (!pending.isEmpty()) return List.of();
                var released = List.copyOf(buffered);
                clear();
                return released;
            }
            return List.of(event);
        }

        private List<CanonicalEvent> failure(CanonicalEvent event) {
            clear();
            return List.of(new CanonicalEvent.Failed(event.schemaVersion(), event.requestId(), event.sequenceNumber(),
                "tool_call_generation_failed", "upstream function arguments do not satisfy the strict tool contract",
                Map.of("param", "tools", "retryable", false)));
        }

        private int payloadSize(CanonicalEvent event) {
            return switch (event) {
                case CanonicalEvent.ToolArgumentsDelta delta -> delta.delta().length();
                case CanonicalEvent.ToolCallCompleted completed -> completed.arguments() == null ? 0 : completed.arguments().length();
                case CanonicalEvent.OutputTextDelta delta -> delta.delta().length();
                case CanonicalEvent.ReasoningDelta delta -> delta.delta().length();
                default -> 0;
            };
        }

        private void clear() {
            pending.clear();
            buffered.clear();
            bufferedCharacters = 0;
        }
    }

    private record PendingCall(Schema schema, StringBuilder arguments) {}
}
