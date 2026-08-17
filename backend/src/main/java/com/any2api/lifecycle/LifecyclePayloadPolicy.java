package com.any2api.lifecycle;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public interface LifecyclePayloadPolicy {
    void contribute(
        String providerId,
        String operation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> payload
    );
}
