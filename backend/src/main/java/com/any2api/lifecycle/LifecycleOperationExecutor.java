package com.any2api.lifecycle;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class LifecycleOperationExecutor {
    private final ProviderLifecycleRegistry local;
    private final LifecycleAutomationClient automation;

    LifecycleOperationExecutor(
        ProviderLifecycleRegistry local,
        LifecycleAutomationClient automation
    ) {
        this.local = local;
        this.automation = automation;
    }

    Mono<LifecycleResult> execute(
        String providerId,
        String externalOperation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> proxyPool
    ) {
        var operation = AutomationOperation.fromExternalName(externalOperation);
        return local.handler(providerId, operation)
            .map(handler -> handler.execute(
                operation, credential, accountMetadata == null ? Map.of() : accountMetadata,
                proxyPool))
            .orElseGet(() -> {
                var payload = new HashMap<String, Object>();
                payload.put("credential", credential);
                payload.put("metadata", accountMetadata == null ? Map.of() : accountMetadata);
                if (proxyPool != null && !proxyPool.isEmpty()) {
                    payload.put("proxy_pool", proxyPool);
                }
                return automation.execute(providerId, externalOperation, Map.copyOf(payload))
                    .map(LifecycleResult::fromAutomation);
            });
    }
}
