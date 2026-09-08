package com.any2api.provider.grok_web;

import com.any2api.lifecycle.AutomationOperation;
import com.any2api.lifecycle.LifecycleResult;
import com.any2api.lifecycle.ProviderLifecycleHandler;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class GrokWebLifecycleHandler implements ProviderLifecycleHandler {
    private final OfficialBrowserTransportClient transport;
    private final OfficialBrowserSemanticCommandFactory semanticCommands;

    GrokWebLifecycleHandler(
        OfficialBrowserTransportClient transport,
        OfficialBrowserSemanticCommandFactory semanticCommands
    ) {
        this.transport = transport;
        this.semanticCommands = semanticCommands;
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
        return transport.request(
                providerId(), "keepalive", semanticCommands.models(), credential,
                proxyPool, affinity(accountMetadata))
            .map(response -> {
                var body = response.body() == null ? "" : response.body();
                var authenticated = response.status() == 200
                    && (body.contains("\"userId\"") || body.contains("\"user_id\""));
                if (authenticated) {
                    return LifecycleResult.healthy(
                        response.credentialPatch(),
                        tools.jackson.databind.node.MissingNode.getInstance());
                }
                return LifecycleResult.failed(
                    response.status() == 401 || response.status() == 403,
                    false,
                    response.status() == 401 || response.status() == 403
                        ? "credential_rejected" : "provider_upstream_error",
                    response.credentialPatch());
            });
    }

    private String affinity(Map<String, Object> metadata) {
        return String.valueOf(metadata.getOrDefault("identity_group_id", "")).trim();
    }
}
