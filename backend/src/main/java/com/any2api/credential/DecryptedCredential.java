package com.any2api.credential;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record DecryptedCredential(
    String type,
    long version,
    Instant expiresAt,
    JsonNode payload
) {
}
