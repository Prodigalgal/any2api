package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModelProbeSchedulerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void schedulesOnlySingleRepresentativeModelPerProvider() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<Object>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn((JdbcClient.MappedQuerySpec) query);
        when(query.optional()).thenReturn(Optional.of("test-model"));

        var properties = new Any2ApiProperties();
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(
            "test-provider", "Test", "test", "1", List.of("test-model"),
            Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE),
            true));
        when(provider.protocolContract()).thenReturn(ProviderProtocolContract.strict());
        when(provider.scheduledModelProbeEnabled()).thenReturn(true);
        when(provider.scheduledProbeModel()).thenReturn(Optional.of("test-model"));
        var providers = ProviderRegistry.allEnabled(List.of(provider));

        var probeService = mock(ModelProbeService.class);
        when(probeService.probe(eq("test-provider"), eq("test-model"))).thenReturn(reactor.core.publisher.Mono.empty());

        try (var executor = Executors.newSingleThreadExecutor()) {
            new ModelProbeScheduler(
                jdbc, executor, probeService, providers, properties)
                .probeStaleModels();
        }

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
            .contains("model.provider_id = :providerId")
            .contains("model.enabled = TRUE")
            .contains("probe.probed_at IS NULL")
            .contains(":preferredModel");
        verify(statement).param("providerId", "test-provider");
        verify(statement).param("preferredModel", "test-model");
        verify(probeService).probe("test-provider", "test-model");
    }
}
