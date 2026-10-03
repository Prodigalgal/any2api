package com.any2api.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.time.Duration;
import com.any2api.protocol.CanonicalEvent;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModelProbeServiceTest {

    @Test
    void localInfrastructureFailureDoesNotOverwriteUpstreamReadiness() {
        var error = new IllegalStateException("runtime is unavailable during rollout");
        verifyProbe(Flux.error(error), result -> StepVerifier.create(result)
            .expectErrorMatches(caught -> caught == error).verify(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"credential_rejected", "anti_bot_rejected"})
    void accountSpecificFailureDoesNotDeclareTheModelUnavailable(String failure) {
        verifyProbe(Flux.just(new CanonicalEvent.Failed(1, "probe", 1, failure, "synthetic failure", Map.of())),
            result -> StepVerifier.create(result).verifyComplete(), false);
    }

    @Test
    void measuredUpstreamFailureStillUpdatesModelState() {
        verifyProbe(Flux.just(new CanonicalEvent.Failed(1, "probe", 1, "upstream_5xx", "synthetic failure", Map.of())),
            result -> StepVerifier.create(result).assertNext(measured -> {
                assertThat(measured.status()).isEqualTo("FAILED");
                assertThat(measured.errorClass()).isEqualTo("upstream_5xx");
            }).verifyComplete(), true);
    }

    private void verifyProbe(Flux<CanonicalEvent> events,
        java.util.function.Consumer<Mono<ModelProbeService.Result>> assertion, boolean persisted) {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        var query = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(1L);
        when(statement.update()).thenReturn(1);
        var catalog = mock(ModelCatalogCache.class);
        when(catalog.invalidate()).thenReturn(Mono.empty());
        var provider = mock(InferenceProvider.class);
        when(provider.modelProbeTimeout()).thenReturn(Duration.ofSeconds(2));
        var registry = mock(ProviderRegistry.class);
        when(registry.require("mimo")).thenReturn(provider);
        var coordinator = mock(InferenceCoordinator.class);
        when(coordinator.executeProbe(any())).thenReturn(events);
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertion.accept(new ModelProbeService(registry, coordinator, jdbc, executor, catalog)
                .probe("mimo", "fixture"));
        }
        verify(statement, persisted ? org.mockito.Mockito.times(1) : never()).update();
        verify(catalog, persisted ? org.mockito.Mockito.times(1) : never()).invalidate();
    }

    @Test
    void recordsReadyEvidenceAndInvalidatesTheCatalog() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var catalog = mock(ModelCatalogCache.class);
        var accountId = UUID.randomUUID();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new ModelProbeService(
                mock(ProviderRegistry.class),
                mock(InferenceCoordinator.class),
                jdbc,
                executor,
                catalog);

            service.recordReadyEvidence(
                "minmax", "MiniMax-M3", accountId, 321,
                Instant.parse("2026-09-09T07:00:00Z"));
        }

        verify(jdbc).sql(org.mockito.ArgumentMatchers.argThat(sql ->
            sql.contains("INSERT INTO model_probe_results")
                && sql.contains("status")
                && sql.contains("ON CONFLICT (provider_id, model_id)")));
        verify(catalog).invalidateAfterCommit();
    }
}
