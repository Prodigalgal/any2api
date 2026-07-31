package com.any2api.provider;

import java.util.Set;

public record ProviderRetryPolicy(
    int maxAttempts,
    Set<String> retryablePreOutputFailures
) {
    private static final ProviderRetryPolicy NONE = new ProviderRetryPolicy(1, Set.of());

    public ProviderRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        retryablePreOutputFailures = retryablePreOutputFailures == null
            ? Set.of() : Set.copyOf(retryablePreOutputFailures);
    }

    public static ProviderRetryPolicy none() {
        return NONE;
    }

    public boolean shouldRetry(String failureType, int completedAttempts) {
        return completedAttempts < maxAttempts
            && retryablePreOutputFailures.contains(failureType);
    }
}
