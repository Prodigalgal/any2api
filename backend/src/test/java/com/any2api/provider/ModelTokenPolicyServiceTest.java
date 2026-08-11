package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ModelTokenPolicyServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsOverridesAboveTheDiscoveredProviderLimit() {
        var catalog = mock(ModelCatalogCache.class);
        when(catalog.find("qwen", "qwen3-max"))
            .thenReturn(Mono.just(Optional.of(entry())));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new ModelTokenPolicyService(
                mock(JdbcClient.class), mock(PlatformTransactionManager.class),
                executor, catalog);

            StepVerifier.create(service.update(new ModelTokenPolicyService.UpdateRequest(
                    "qwen", "qwen3-max", 300_000L, null, null)))
                .expectErrorSatisfies(error -> assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed the discovered provider limit 262144"))
                .verify();
        }
    }

    @Test
    void exposesDiscoveredOverrideAndEffectiveLimitsSeparately() {
        var catalog = mock(ModelCatalogCache.class);
        when(catalog.list()).thenReturn(Mono.just(List.of(entry())));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new ModelTokenPolicyService(
                mock(JdbcClient.class), mock(PlatformTransactionManager.class),
                executor, catalog);

            var values = service.list().block();

            assertThat(values).singleElement().satisfies(value -> {
                assertThat(value.discovered().maxContextTokens()).isEqualTo(262_144L);
                assertThat(value.overrides().maxContextTokens()).isEqualTo(200_000L);
                assertThat(value.effective().maxContextTokens()).isEqualTo(200_000L);
            });
        }
    }

    private ModelCatalogCache.Entry entry() {
        var discovered = mapper.createObjectNode()
            .put("max_context_tokens", 262_144)
            .put("max_input_tokens", 245_760)
            .put("max_output_tokens", 16_384);
        var effective = discovered.deepCopy()
            .put("max_context_tokens", 200_000)
            .put("max_input_tokens", 180_000)
            .put("max_output_tokens", 12_000);
        return new ModelCatalogCache.Entry(
            "qwen3-max", "Qwen 3 Max", "qwen", "Qwen",
            effective, discovered, 200_000L, 180_000L, 12_000L,
            "OFFICIAL", mapper.createObjectNode(), List.of(), 1,
            true, "READY", 4, 3, 1, 10, 10, 1.0,
            100, 200, null, null, "READY", null, null);
    }
}
