package com.any2api.observability;

import java.util.Map;

public record OperationContext(
    String correlationId,
    String aggregateType,
    String aggregateId,
    int attempt
) {
    public OperationContext {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
        if (correlationId.length() > 100) {
            throw new IllegalArgumentException("correlationId must not exceed 100 characters");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateType.length() > 32) {
            throw new IllegalArgumentException("aggregateType must not exceed 32 characters");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (aggregateId.length() > 255) {
            throw new IllegalArgumentException("aggregateId must not exceed 255 characters");
        }
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
    }

    public Map<String, Object> toWire() {
        return Map.of(
            "correlation_id", correlationId,
            "aggregate_type", aggregateType,
            "aggregate_id", aggregateId,
            "attempt", attempt);
    }
}
