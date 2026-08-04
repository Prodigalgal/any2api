package com.any2api.account;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AccountView(
    UUID id,
    String providerId,
    String externalId,
    String email,
    AccountStatus status,
    boolean enabled,
    int maxConcurrency,
    int priority,
    int weight,
    Instant cooldownUntil,
    Instant expiresAt,
    Map<String, Object> metadata,
    long version,
    long requestCount,
    long successCount,
    long failureCount,
    Instant lastUsedAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastError,
    Instant createdAt,
    Instant updatedAt
) {
    public static AccountView from(AccountEntity account) {
        return new AccountView(
            account.getId(), account.getProviderId(), account.getExternalId(), account.getEmail(),
            account.getStatus(), account.isEnabled(), account.getMaxConcurrency(),
            account.getPriority(), account.getWeight(), account.getCooldownUntil(),
            account.getExpiresAt(), account.getMetadata(), account.getVersion(),
            account.getRequestCount(), account.getSuccessCount(), account.getFailureCount(),
            account.getLastUsedAt(), account.getLastSuccessAt(), account.getLastFailureAt(),
            account.getLastError(), account.getCreatedAt(), account.getUpdatedAt());
    }
}
