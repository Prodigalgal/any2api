package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModelProbeSchedulerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void schedulesEveryEnabledStaleModelRegardlessOfRandomRoutingRole() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<Object>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn((JdbcClient.MappedQuerySpec) query);
        when(query.list()).thenReturn(List.of());
        var properties = new Any2ApiProperties();

        try (var executor = Executors.newSingleThreadExecutor()) {
            new ModelProbeScheduler(
                jdbc, executor, mock(ModelProbeService.class), properties).probeStaleModels();
        }

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
            .contains("model.enabled = TRUE")
            .contains("probe.probed_at IS NULL")
            .doesNotContain("cardinality(model.random_roles)");
    }
}
