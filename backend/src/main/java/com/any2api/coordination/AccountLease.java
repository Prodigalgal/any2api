package com.any2api.coordination;

import java.time.Instant;
import java.util.UUID;

public record AccountLease(
    String providerId,
    UUID accountId,
    String ownerToken,
    long fencingToken,
    Instant expiresAt
) {
}

