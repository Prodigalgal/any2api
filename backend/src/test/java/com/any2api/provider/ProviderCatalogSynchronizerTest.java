package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ProviderCatalogSynchronizerTest {

    @Test
    void reconcilesInstallationWithoutOverwritingAdministrativeEnablement() {
        var registry = mock(ProviderRegistry.class);
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(manifest("alpha"));
        when(registry.plugins()).thenReturn(List.of(provider));
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        var synchronizer = new ProviderCatalogSynchronizer(
            registry, jdbc, null, mock(ObjectMapper.class), null, null);
        synchronizer.synchronizeInstalledProviders();

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(5)).sql(sql.capture());
        assertThat(sql.getAllValues().getFirst()).contains("installed = FALSE");
        assertThat(sql.getAllValues().get(1))
            .contains("installed, adapter_version")
            .contains("installed = TRUE")
            .doesNotContain("enabled = TRUE");
        assertThat(sql.getAllValues().get(2))
            .contains("UPDATE accounts")
            .contains("enabled = FALSE")
            .contains("installed = FALSE");
        assertThat(sql.getAllValues().get(3)).contains("ProviderPluginRemoved");
        assertThat(sql.getAllValues().get(4)).contains("ProviderPluginRemoved");
    }

    private ProviderManifest manifest(String id) {
        return new ProviderManifest(id, id, "test-v1", "1", List.of(), Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.RESPONSES, SupportLevel.NATIVE), true);
    }
}
