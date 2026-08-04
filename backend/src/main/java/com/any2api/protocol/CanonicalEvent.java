package com.any2api.protocol;

import java.util.Map;

public sealed interface CanonicalEvent permits
    CanonicalEvent.ResponseStarted,
    CanonicalEvent.ReasoningDelta,
    CanonicalEvent.OutputTextDelta,
    CanonicalEvent.ToolCallStarted,
    CanonicalEvent.ToolArgumentsDelta,
    CanonicalEvent.ToolCallCompleted,
    CanonicalEvent.Usage,
    CanonicalEvent.Completed,
    CanonicalEvent.Failed {

    int schemaVersion();

    String requestId();

    long sequenceNumber();

    record ResponseStarted(int schemaVersion, String requestId, long sequenceNumber, String responseId)
        implements CanonicalEvent {
    }

    record ReasoningDelta(int schemaVersion, String requestId, long sequenceNumber, String delta)
        implements CanonicalEvent {
    }

    record OutputTextDelta(int schemaVersion, String requestId, long sequenceNumber, String delta)
        implements CanonicalEvent {
    }

    record ToolCallStarted(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        String toolCallId,
        String name
    ) implements CanonicalEvent {
    }

    record ToolArgumentsDelta(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        String toolCallId,
        String delta
    ) implements CanonicalEvent {
    }

    record ToolCallCompleted(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        String toolCallId,
        String arguments
    ) implements CanonicalEvent {
    }

    record Usage(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        UsageSource source,
        long rawInputTokens,
        long rawOutputTokens,
        long rawCacheReadTokens
    ) implements CanonicalEvent {
        public Usage(
            int schemaVersion,
            String requestId,
            long sequenceNumber,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            UsageSource source
        ) {
            this(schemaVersion, requestId, sequenceNumber, inputTokens, outputTokens,
                cacheReadTokens, source, inputTokens, outputTokens, cacheReadTokens);
        }

        public Usage(
            int schemaVersion,
            String requestId,
            long sequenceNumber,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens
        ) {
            this(schemaVersion, requestId, sequenceNumber, inputTokens, outputTokens,
                cacheReadTokens, UsageSource.UPSTREAM,
                inputTokens, outputTokens, cacheReadTokens);
        }

        public Usage {
            source = source == null ? UsageSource.UPSTREAM : source;
        }
    }

    record Completed(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        String finishReason
    ) implements CanonicalEvent {
    }

    record Failed(
        int schemaVersion,
        String requestId,
        long sequenceNumber,
        String errorType,
        String message,
        Map<String, Object> detail
    ) implements CanonicalEvent {
    }
}
