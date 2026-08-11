package com.any2api.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.lifecycle.AutomationOperation;
import com.any2api.lifecycle.AutomationProviderCatalog;
import com.any2api.lifecycle.ProviderLifecycleRegistry;
import com.any2api.lifecycle.ProviderLifecycleHandler;
import com.any2api.lifecycle.LifecycleResult;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ProviderCatalogControllerTest {

    @Test
    void returnsInstalledPluginsWithoutDependingOnDiscoveredModels() {
        var registry = ProviderRegistry.allEnabled(
            List.of(provider("zeta"), provider("alpha")));
        var automation = mock(AutomationProviderCatalog.class);
        when(automation.operationsFor("alpha")).thenReturn(Set.of(AutomationOperation.REGISTER));
        when(automation.operationsFor("zeta")).thenReturn(Set.of());
        when(automation.ready()).thenReturn(true);
        var localHandler = new ProviderLifecycleHandler() {
            @Override public String providerId() { return "alpha"; }
            @Override public Set<AutomationOperation> operations() {
                return Set.of(AutomationOperation.KEEPALIVE);
            }
            @Override public reactor.core.publisher.Mono<LifecycleResult> execute(
                AutomationOperation operation,
                tools.jackson.databind.JsonNode credential,
                Map<String, Object> accountMetadata,
                Map<String, Object> proxyPool
            ) {
                return reactor.core.publisher.Mono.empty();
            }
        };
        var response = new ProviderCatalogController(registry, automation,
            new ProviderLifecycleRegistry(List.of(localHandler), registry)).providers();

        assertThat(response.get("object")).isEqualTo("list");
        assertThat(response.get("automationCatalogReady")).isEqualTo(true);
        assertThat((List<?>) response.get("data"))
            .extracting(item -> ((ProviderCatalogController.ProviderDescriptor) item).id())
            .containsExactly("alpha", "zeta");
        assertThat((List<?>) response.get("data"))
            .first()
            .extracting(item -> ((ProviderCatalogController.ProviderDescriptor) item)
                .lifecycleOperations())
            .isEqualTo(List.of("keepalive", "register"));
        assertThat((List<?>) response.get("data"))
            .first()
            .extracting(item -> ((ProviderCatalogController.ProviderDescriptor) item)
                .protocolContract().providerOptions())
            .isEqualTo(Map.of("flag", ProviderProtocolContract.OptionType.BOOLEAN));
    }

    private InferenceProvider provider(String id) {
        return new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    id, id.toUpperCase(), "test-v1", "1", List.of(), Map.of(
                        ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                        ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
            }

            @Override
            public ProviderProtocolContract protocolContract() {
                return new ProviderProtocolContract(
                    Map.of("flag", ProviderProtocolContract.OptionType.BOOLEAN),
                    Set.of(), Set.of(), Set.of());
            }

            @Override
            public Flux<CanonicalEvent> generate(
                CanonicalRequest request,
                ProviderExecutionContext context,
                LeasedProviderAccount account
            ) {
                return Flux.empty();
            }

            @Override
            public ProviderFailure classify(Throwable error) {
                return new ProviderFailure("test", "test", false, Map.of());
            }
        };
    }
}
