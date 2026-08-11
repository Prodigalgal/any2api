package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

class ProviderLifecycleRegistryTest {
    private final ProviderRegistry providers =
        ProviderRegistry.allEnabled(List.of(provider("alpha")));

    @Test
    void indexesLocalOperationsWithoutProviderBranches() {
        var registry = new ProviderLifecycleRegistry(
            List.of(handler("alpha", AutomationOperation.KEEPALIVE)), providers);

        assertThat(registry.operationsFor("alpha"))
            .containsExactly(AutomationOperation.KEEPALIVE);
        assertThat(registry.handler("alpha", AutomationOperation.KEEPALIVE)).isPresent();
        assertThat(registry.handler("alpha", AutomationOperation.REGISTER)).isEmpty();
    }

    @Test
    void rejectsDuplicateAndUnknownProviderHandlers() {
        assertThatThrownBy(() -> new ProviderLifecycleRegistry(List.of(
            handler("alpha", AutomationOperation.KEEPALIVE),
            handler("alpha", AutomationOperation.REAUTHENTICATE)), providers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new ProviderLifecycleRegistry(
            List.of(handler("missing", AutomationOperation.KEEPALIVE)), providers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown provider");
    }

    private ProviderLifecycleHandler handler(String id, AutomationOperation operation) {
        return new ProviderLifecycleHandler() {
            @Override public String providerId() { return id; }
            @Override public Set<AutomationOperation> operations() { return Set.of(operation); }
            @Override public Mono<LifecycleResult> execute(
                AutomationOperation ignored,
                JsonNode credential,
                Map<String, Object> accountMetadata,
                Map<String, Object> proxyPool
            ) {
                return Mono.empty();
            }
        };
    }

    private InferenceProvider provider(String id) {
        return new InferenceProvider() {
            @Override public ProviderManifest manifest() {
                return new ProviderManifest(id, id, "test", "1", List.of(), Map.of(
                    ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
            }
            @Override public Flux<CanonicalEvent> generate(
                CanonicalRequest request,
                ProviderExecutionContext context,
                LeasedProviderAccount account
            ) {
                return Flux.empty();
            }
            @Override public ProviderFailure classify(Throwable error) {
                return new ProviderFailure("test", "test", false, Map.of());
            }
        };
    }
}
