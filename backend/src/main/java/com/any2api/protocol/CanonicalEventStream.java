package com.any2api.protocol;

import java.util.HashSet;
import java.util.Set;
import reactor.core.publisher.Flux;

public final class CanonicalEventStream {
    private CanonicalEventStream() {
    }

    public static Flux<CanonicalEvent> enforce(
        CanonicalRequest request,
        Flux<CanonicalEvent> source
    ) {
        return Flux.defer(() -> {
            var state = new State(request.requestId());
            return source.<CanonicalEvent>handle((event, sink) -> {
                state.accept(event);
                sink.next(event);
            }).concatWith(Flux.defer(() -> state.terminal
                ? Flux.<CanonicalEvent>empty()
                : Flux.error(new CanonicalProtocolException("missing_terminal_event"))));
        });
    }

    private static final class State {
        private final String requestId;
        private final Set<String> openTools = new HashSet<>();
        private long sequence = Long.MIN_VALUE;
        private boolean started;
        private boolean terminal;
        private boolean usage;

        private State(String requestId) {
            this.requestId = requestId;
        }

        private void accept(CanonicalEvent event) {
            if (event.schemaVersion() != 1) fail("unsupported_schema_version");
            if (!requestId.equals(event.requestId())) fail("request_id_mismatch");
            if (event.sequenceNumber() <= sequence) fail("non_monotonic_sequence");
            sequence = event.sequenceNumber();
            if (terminal) fail("event_after_terminal");

            if (event instanceof CanonicalEvent.Failed) {
                terminal = true;
                return;
            }
            if (event instanceof CanonicalEvent.ResponseStarted startedEvent) {
                if (started) fail("duplicate_response_started");
                if (startedEvent.responseId() == null || startedEvent.responseId().isBlank()) {
                    fail("blank_response_id");
                }
                started = true;
                return;
            }
            if (!started) fail("payload_before_response_started");

            if (event instanceof CanonicalEvent.ToolCallStarted tool) {
                if (tool.toolCallId() == null || tool.toolCallId().isBlank()
                    || tool.name() == null || tool.name().isBlank()) {
                    fail("invalid_tool_call_started");
                }
                if (!openTools.add(tool.toolCallId())) fail("duplicate_tool_call_started");
            } else if (event instanceof CanonicalEvent.ToolArgumentsDelta arguments) {
                if (!openTools.contains(arguments.toolCallId())) {
                    fail("tool_arguments_before_start");
                }
            } else if (event instanceof CanonicalEvent.ToolCallCompleted completed) {
                if (!openTools.remove(completed.toolCallId())) {
                    fail("tool_completed_without_start");
                }
            } else if (event instanceof CanonicalEvent.Usage) {
                if (usage) fail("duplicate_usage");
                usage = true;
            } else if (event instanceof CanonicalEvent.Completed) {
                if (!openTools.isEmpty()) fail("terminal_with_open_tool_calls");
                terminal = true;
            }
        }

        private void fail(String violation) {
            throw new CanonicalProtocolException(violation);
        }
    }
}
