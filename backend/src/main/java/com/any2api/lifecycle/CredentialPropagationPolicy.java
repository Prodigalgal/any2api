package com.any2api.lifecycle;

import com.any2api.account.AccountEntity;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

public interface CredentialPropagationPolicy {
    void propagate(AccountEntity source, JsonNode recoveredCredential, Instant expiresAt);
}
