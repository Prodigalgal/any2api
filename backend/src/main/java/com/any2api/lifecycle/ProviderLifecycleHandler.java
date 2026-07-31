package com.any2api.lifecycle;

import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

public interface ProviderLifecycleHandler {
    String providerId();

    Set<AutomationOperation> operations();

    Mono<LifecycleResult> execute(
        AutomationOperation operation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> proxyPool
    );

    default Mono<LifecycleResult> execute(
        AutomationOperation operation,
        JsonNode credential,
        Map<String, Object> proxyPool
    ) {
        return execute(operation, credential, Map.of(), proxyPool);
    }
}
