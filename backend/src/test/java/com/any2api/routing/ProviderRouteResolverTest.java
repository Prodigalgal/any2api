package com.any2api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.OpenAiBridgeProvider;
import com.any2api.provider.ProviderRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderRouteResolverTest {

    private ProviderRouteResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProviderRouteResolver(new ProviderRegistry(List.of(
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
        var dynamicResolver = new ProviderRouteResolver(new ProviderRegistry(List.of(provider("acme"))));

        assertThat(dynamicResolver.resolve("/acme/v1/responses", "acme-ultra"))
            .isEqualTo(new ResolvedRoute("acme", "acme-ultra"));
        assertThat(dynamicResolver.resolve("/v1/responses", "acme/acme-ultra"))
            .isEqualTo(new ResolvedRoute("acme", "acme-ultra"));
    }

    @Test
    void duplicateProviderPluginIdsFailAtStartup() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(provider("acme"), provider("acme"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate provider id");
    }

    private InferenceProvider provider(String id) {
        var properties = new Any2ApiProperties();
        properties.getProviders().put(id, new Any2ApiProperties.Provider());
        return new OpenAiBridgeProvider(
            properties,
            id,
            id,
            "test-v1",
            List.of(id + "-model"),
            Map.of()
        ) {};
    }
}
