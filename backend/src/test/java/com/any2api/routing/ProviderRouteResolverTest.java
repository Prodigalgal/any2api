package com.any2api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderInstallationCatalog;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ProviderRouteResolverTest {

    private ProviderRouteResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProviderRouteResolver(ProviderRegistry.allEnabled(List.of(
            provider("alpha"),
            provider("beta"))));
    }

    @Test
    void unifiedRouteRequiresAndStripsProviderNamespace() {
        assertThat(resolver.resolve("/v1/chat/completions", "alpha/model-one"))
            .isEqualTo(new ResolvedRoute("alpha", "model-one"));
    }

    @Test
    void providerPathAcceptsUnqualifiedModel() {
        assertThat(resolver.resolve("/beta/v1/chat/completions", "model-two"))
            .isEqualTo(new ResolvedRoute("beta", "model-two"));
    }

    @Test
    void conflictingPathAndModelAreRejected() {
        assertThatThrownBy(() -> resolver.resolve(
            "/beta/v1/chat/completions",
            "alpha/model-one"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicts");
    }

    @Test
    void unqualifiedUnifiedModelIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("/v1/chat/completions", "model-one"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider/model");
    }

    @Test
    void newlyConfiguredProviderNeedsNoRoutingCodeChange() {
        var dynamicResolver = new ProviderRouteResolver(
            ProviderRegistry.allEnabled(List.of(provider("acme"))));

        assertThat(dynamicResolver.resolve("/acme/v1/responses", "acme-ultra"))
            .isEqualTo(new ResolvedRoute("acme", "acme-ultra"));
        assertThat(dynamicResolver.resolve("/v1/responses", "acme/acme-ultra"))
            .isEqualTo(new ResolvedRoute("acme", "acme-ultra"));
    }

    @Test
    void duplicateProviderPluginIdsFailAtStartup() {
        assertThatThrownBy(() -> ProviderRegistry.allEnabled(
            List.of(provider("acme"), provider("acme"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate provider id");
    }

    @Test
    void administrativelyDisabledProviderIsAbsentFromEveryRoute() {
        var installations = mock(ProviderInstallationCatalog.class);
        when(installations.isEnabled("alpha")).thenReturn(false);
        when(installations.isEnabled("beta")).thenReturn(true);
        var registry = new ProviderRegistry(
            List.of(provider("alpha"), provider("beta")), installations);
        var filteredResolver = new ProviderRouteResolver(registry);

        assertThat(registry.list()).extracting(ProviderManifest::id).containsExactly("beta");
        assertThatThrownBy(() -> filteredResolver.resolve(
            "/alpha/v1/chat/completions", "alpha-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider is disabled");
    }

    private InferenceProvider provider(String id) {
        return new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    id, id, "test-v1", "1", List.of(id + "-model"), Map.of(
                        ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                        ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
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
