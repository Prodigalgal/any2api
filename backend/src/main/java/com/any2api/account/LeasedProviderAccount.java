package com.any2api.account;

import com.any2api.coordination.AccountLease;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record LeasedProviderAccount(
    UUID accountId,
    String providerId,
    String externalId,
    String email,
    long credentialVersion,
    Instant credentialExpiresAt,
    JsonNode credential,
    Map<String, Object> metadata,
    AccountLease lease
) {
}
