package com.any2api.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModelProbeServiceTest {

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
