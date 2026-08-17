package com.any2api.provider.xai_identity;

import com.any2api.lifecycle.LifecyclePayloadPolicy;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class XaiLifecyclePayloadPolicy implements LifecyclePayloadPolicy {
    @Override
    public void contribute(
        String providerId,
        String operation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> payload
    ) {
        if ("grok".equals(providerId)
            && "reauthenticate".equals(operation)
            && Boolean.TRUE.equals(accountMetadata.get("xai_force_sso_refresh"))) {
            payload.put("force_sso_refresh", true);
        }
    }
}
