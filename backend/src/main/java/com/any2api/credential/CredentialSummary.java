package com.any2api.credential;

import java.time.Instant;

public record CredentialSummary(
    boolean configured,
    String type,
    long version,
    Instant expiresAt,
    Instant updatedAt
) {
    public static CredentialSummary missing() {
        return new CredentialSummary(false, null, 0, null, null);
    }
}
