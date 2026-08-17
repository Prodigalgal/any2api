package com.any2api.lifecycle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.any2api.observability.OperationContext;
import com.any2api.settings.RuntimeSettingsService;
import com.any2api.runtime.ProviderRuntimeRuleService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class LifecycleOperationExecutor {
    private final ProviderLifecycleRegistry local;
    private final LifecycleAutomationClient automation;
    private final RuntimeSettingsService runtimeSettings;
    private final ProviderRuntimeRuleService runtimeRules;
    private final List<LifecyclePayloadPolicy> payloadPolicies;

    LifecycleOperationExecutor(
        ProviderLifecycleRegistry local,
        LifecycleAutomationClient automation,
        RuntimeSettingsService runtimeSettings,
        ProviderRuntimeRuleService runtimeRules,
        List<LifecyclePayloadPolicy> payloadPolicies
    ) {
        this.local = local;
        this.automation = automation;
        this.runtimeSettings = runtimeSettings;
        this.runtimeRules = runtimeRules;
        this.payloadPolicies = List.copyOf(payloadPolicies);
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
                var metadata = accountMetadata == null ? Map.<String, Object>of() : accountMetadata;
                payload.put("credential", credential);
                payload.put("metadata", metadata);
                for (var policy : payloadPolicies) {
                    policy.contribute(
                        providerId, externalOperation, credential, metadata, payload);
                }
                if (proxyPool != null && !proxyPool.isEmpty()) {
                    payload.put("proxy_pool", proxyPool);
                    var affinityKey = credential.path("proxy_affinity_key").asText("").trim();
                    if (!affinityKey.isBlank()) {
                        payload.put("proxy_affinity_key", affinityKey);
                        payload.put("strict_proxy_affinity", true);
                        payload.put("proxy_node_offset", Math.max(0,
                            credential.path("proxy_node_offset").asInt(0)));
                    }
                }
                runtimeSettings.applyLifecycleParameters(
                    payload, providerId, externalOperation);
                if ("keepalive".equals(externalOperation)) {
                    runtimeRules.findPlan(providerId)
                        .ifPresent(plan -> payload.put("runtime_plan", plan));
                }
                runtimeSettings.applyMailSettings(payload, null);
                return automation.execute(
                        providerId, externalOperation, Map.copyOf(payload), context)
                    .map(LifecycleResult::fromAutomation);
            });
    }
}
