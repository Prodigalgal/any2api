package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcProviderInstallationCatalogTest {

    @Test
    @SuppressWarnings("unchecked")
    void refreshChangesTheEnabledSnapshotWithoutRebuildingTheCatalog() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of("alpha"), List.of("beta"));
        var catalog = new JdbcProviderInstallationCatalog(jdbc);

        catalog.refresh();
        assertThat(catalog.isEnabled("alpha")).isTrue();
        assertThat(catalog.isEnabled("beta")).isFalse();

        catalog.refresh();
        assertThat(catalog.isEnabled("alpha")).isFalse();
        assertThat(catalog.isEnabled("beta")).isTrue();
    }
}
