package com.any2api.auth;

import java.util.Set;

public record ApiKeyProviderScope(
    String providerId,
    boolean allModels,
    Set<String> models
) {
    public ApiKeyProviderScope {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id is required");
        }
        models = Set.copyOf(models);
        if (allModels && !models.isEmpty()) {
            throw new IllegalArgumentException("all-model scope cannot contain selected models");
        }
        if (!allModels && models.isEmpty()) {
            throw new IllegalArgumentException("selected-model scope requires at least one model");
        }
    }

    public static ApiKeyProviderScope allModels(String providerId) {
        return new ApiKeyProviderScope(providerId, true, Set.of());
    }

    public static ApiKeyProviderScope selectedModels(String providerId, Set<String> models) {
        return new ApiKeyProviderScope(providerId, false, models);
    }

    public boolean allows(String model) {
        return allModels || models.contains(model);
    }
}
