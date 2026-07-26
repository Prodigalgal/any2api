package com.any2api.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderExecutionContext(
    String requestId,
    UUID accountId,
    String credentialVersion,
    String leaseOwnerToken,
    long fencingToken,
    Instant deadline
) {
}

