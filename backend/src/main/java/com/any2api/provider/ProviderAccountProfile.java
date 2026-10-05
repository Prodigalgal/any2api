package com.any2api.provider;

import java.util.Map;
import java.util.UUID;

public record ProviderAccountProfile(UUID accountId, Map<String, Object> metadata) {
    public ProviderAccountProfile {
        // JSON metadata may contain null values; retain them in an immutable snapshot.
        metadata = metadata == null ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
}
