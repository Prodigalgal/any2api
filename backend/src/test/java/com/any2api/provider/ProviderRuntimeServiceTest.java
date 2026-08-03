package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.coordination.PostgresAdvisoryLocks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ProviderRuntimeServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void hotUnplugQuarantinesWorkAndRefreshesOnlyAfterCommit() {
        var registry = mock(ProviderRegistry.class);
        var installations = mock(ProviderInstallationCatalog.class);
        var locks = mock(PostgresAdvisoryLocks.class);
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<ProviderRuntimeService.ProviderRuntimeView>)
            mock(JdbcClient.MappedQuerySpec.class);
        var provider = mock(InferenceProvider.class);
        var manifest = new ProviderManifest(
            "alpha", "Alpha", "test-v1", "1", List.of("alpha-model"), Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
        var expected = new ProviderRuntimeService.ProviderRuntimeView(
            "alpha", "Alpha", "test-v1", List.of("alpha-model"), manifest.capabilities(),
            true, false, 3, 0, 1);
        when(provider.manifest()).thenReturn(manifest);
        when(registry.requirePlugin("alpha")).thenReturn(provider);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(statement.query(any(RowMapper.class))).thenReturn((JdbcClient.MappedQuerySpec) query);
        when(query.optional()).thenReturn(Optional.of(expected));
        var service = new ProviderRuntimeService(
            registry, installations, locks, jdbc, mock(ModelCatalogCache.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.setEnabled("alpha", false)).isEqualTo(expected);
            verify(installations, never()).refresh();
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
            verify(installations).refresh();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(locks).lockTransaction("provider:alpha:runtime");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(5)).sql(sql.capture());
        assertThat(sql.getAllValues())
            .anySatisfy(value -> assertThat(value).contains("UPDATE providers"))
            .anySatisfy(value -> assertThat(value).contains("UPDATE accounts", "enabled = FALSE"))
            .anySatisfy(value -> assertThat(value).contains(
                "UPDATE scheduled_actions", "ProviderHotUnplugged"))
            .anySatisfy(value -> assertThat(value).contains(
                "UPDATE registration_jobs", "ProviderHotUnplugged"));
    }
}
