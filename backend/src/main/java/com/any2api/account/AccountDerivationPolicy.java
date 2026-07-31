package com.any2api.account;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Projects one upstream identity into independently managed provider-channel accounts. */
public interface AccountDerivationPolicy {

    DerivationPlan derive(AccountSeed source);

    record AccountSeed(
        String providerId,
        String externalId,
        String email,
        Instant expiresAt,
        Instant credentialExpiresAt,
        Map<String, Object> metadata,
        JsonNode credential
    ) {}

    record DerivationPlan(
        Map<String, Object> sourceMetadata,
        List<DerivedAccount> accounts
    ) {
        public static DerivationPlan none() {
            return new DerivationPlan(Map.of(), List.of());
        }
    }

    record DerivedAccount(
        String providerId,
        String externalId,
        Map<String, Object> metadata,
        JsonNode credential
    ) {}
}
