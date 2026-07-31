package com.any2api.provider;

import java.util.Map;

public record DiscoveredModel(String id, String displayName, Map<String, Object> metadata) {
    public DiscoveredModel {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("discovered model id is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
