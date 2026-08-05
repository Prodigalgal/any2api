package com.any2api.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.any2api.observability.OperationContext;
import com.any2api.settings.RuntimeSettingsService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class LifecycleOperationExecutor {
    private final ProviderLifecycleRegistry local;
    private final LifecycleAutomationClient automation;
    private final RuntimeSettingsService runtimeSettings;

    LifecycleOperationExecutor(
        ProviderLifecycleRegistry local,
        LifecycleAutomationClient automation,
        RuntimeSettingsService runtimeSettings
    ) {
        this.local = local;
        this.automation = automation;
        this.runtimeSettings = runtimeSettings;
    }

    Mono<LifecycleResult> execute(
        String providerId,
        String externalOperation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> proxyPool
    ) {
        var correlationId = UUID.randomUUID().toString();
        return execute(providerId, externalOperation, credential, accountMetadata, proxyPool,
            new OperationContext(correlationId, "ACCOUNT", correlationId, 1));
    }

    Mono<LifecycleResult> execute(
        String providerId,
        String externalOperation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> proxyPool,
        OperationContext context
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
                    var affinityKey = credential.path("proxy_affinity_key").asText("").trim();
                    if (!affinityKey.isBlank()) {
                        payload.put("proxy_affinity_key", affinityKey);
                        payload.put("strict_proxy_affinity", true);
                    }
                }
                runtimeSettings.applyMailSettings(payload, null);
                return automation.execute(
                        providerId, externalOperation, Map.copyOf(payload), context)
                    .map(LifecycleResult::fromAutomation);
            });
    }
}
