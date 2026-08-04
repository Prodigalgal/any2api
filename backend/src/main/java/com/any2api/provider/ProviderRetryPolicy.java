package com.any2api.provider;

import java.util.Set;

public record ProviderRetryPolicy(
    int maxAttempts,
    Set<String> retryablePreOutputFailures
) {
    private static final ProviderRetryPolicy NONE = new ProviderRetryPolicy(1, Set.of());
    private static final Set<String> STANDARD_FAILURES = Set.of(
        "empty_model_response",
        "credential_rejected",
        "account_blocked",
        "rate_limited",
        "quota_exhausted");

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

    public static ProviderRetryPolicy standard() {
        return standard(3);
    }

    public static ProviderRetryPolicy standard(int maxAttempts) {
        return new ProviderRetryPolicy(maxAttempts, STANDARD_FAILURES);
    }

    public boolean shouldRetry(String failureType, int completedAttempts) {
        return completedAttempts < maxAttempts
            && retryablePreOutputFailures.contains(failureType);
    }
}
