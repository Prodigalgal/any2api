package com.any2api.provider;

import java.util.Map;
import java.util.UUID;

public record ProviderAccountProfile(UUID accountId, Map<String, Object> metadata) {
    public ProviderAccountProfile {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
