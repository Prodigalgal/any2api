package com.any2api.account;

import java.time.Instant;
import java.util.UUID;

public record AccountListItemView(
    UUID id,
    String providerId,
    String externalId,
    String email,
    AccountStatus status,
    boolean enabled,
    Instant expiresAt,
    long requestCount,
    long successCount,
    long failureCount,
    String lastError,
    Instant updatedAt
) {
    static AccountListItemView from(AccountEntity account) {
        return new AccountListItemView(
            account.getId(), account.getProviderId(), account.getExternalId(), account.getEmail(),
            account.getStatus(), account.isEnabled(), account.getExpiresAt(),
            account.getRequestCount(), account.getSuccessCount(), account.getFailureCount(),
            account.getLastError(), account.getUpdatedAt());
    }
}
