package com.any2api.provider.grok_web;

import com.any2api.lifecycle.AutomationOperation;
import com.any2api.lifecycle.LifecycleResult;
import com.any2api.lifecycle.ProviderLifecycleHandler;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class GrokWebLifecycleHandler implements ProviderLifecycleHandler {
    private final GrokWebProtocolClient protocol;

    GrokWebLifecycleHandler(GrokWebProtocolClient protocol) {
        this.protocol = protocol;
    }

    @Override public String providerId() { return "grok_web"; }

    @Override public Set<AutomationOperation> operations() {
        return Set.of(AutomationOperation.KEEPALIVE);
    }

    @Override
    public Mono<LifecycleResult> execute(
        AutomationOperation operation,
        JsonNode credential,
        Map<String, Object> accountMetadata,
        Map<String, Object> proxyPool
    ) {
        if (operation != AutomationOperation.KEEPALIVE) {
            return Mono.error(new IllegalArgumentException(
                "unsupported Grok Web local lifecycle operation: " + operation.externalName()));
        }
        return protocol.keepalive(
            credential, proxyPool, affinity(accountMetadata)).map(result -> result.healthy()
            ? LifecycleResult.healthy(result.credentialPatch(), result.metadataPatch())
            : LifecycleResult.failed(
                result.authExpired(), result.terminal(), result.errorClass(),
                result.credentialPatch()));
    }

    private String affinity(Map<String, Object> metadata) {
        return String.valueOf(metadata.getOrDefault("identity_group_id", "")).trim();
    }
}
