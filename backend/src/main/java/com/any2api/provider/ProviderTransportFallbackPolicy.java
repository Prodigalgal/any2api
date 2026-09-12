package com.any2api.provider;

import java.util.Set;

/**
 * Keeps the failure classes eligible for AUTO's API-to-Runtime fallback in one place.
 * Request validation, credential failures and quota exhaustion are deliberately excluded.
 */
public final class ProviderTransportFallbackPolicy {
    private static final Set<String> RETRYABLE_API_FAILURES = Set.of(
        "provider_transport_error", "provider_upstream_error", "upstream_unavailable",
        "network_error", "upstream_5xx", "anti_bot_rejected", "rate_limited");

    private ProviderTransportFallbackPolicy() {
    }

    public static boolean allowsRuntimeFallback(String failureType) {
        return RETRYABLE_API_FAILURES.contains(failureType);
    }
}
