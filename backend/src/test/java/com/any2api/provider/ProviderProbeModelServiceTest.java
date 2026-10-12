package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProviderProbeModelServiceTest {

    @Test
    void rejectsAdvancedModelsFromBeingConfiguredAsProbeTarget() {
        var jdbc = mock(JdbcClient.class);
        var service = new ProviderProbeModelService(jdbc);

        assertThatThrownBy(() -> service.set("arena", "arena/Max"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advanced/heavy model");

        assertThatThrownBy(() -> service.set("deepseek", "deepseek-reasoner"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advanced/heavy model");

        assertThatThrownBy(() -> service.set("anthropic", "claude-3-7-sonnet"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advanced/heavy model");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsNonExistentModelsForProvider() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(0L);

        var service = new ProviderProbeModelService(jdbc);

        assertThatThrownBy(() -> service.set("google", "non-existent-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist under provider");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void persistsValidProbeModelAndUpdatesCache() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(1L);
        when(statement.update()).thenReturn(1);

        var service = new ProviderProbeModelService(jdbc);
        service.set("deepseek", "deepseek-chat");

        assertThat(service.configuredProbeModel("deepseek")).contains("deepseek-chat");
        assertThat(service.resolveProbeModel("deepseek", Optional.of("fallback-model")))
            .contains("deepseek-chat");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("jsonb_set"))).isTrue();
    }

    @Test
    void clearsProbeModelWhenGivenBlank() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        var service = new ProviderProbeModelService(jdbc);
        service.set("deepseek", "");

        assertThat(service.configuredProbeModel("deepseek")).isEmpty();
        assertThat(service.resolveProbeModel("deepseek", Optional.of("fallback-model")))
            .contains("fallback-model");
    }
}
