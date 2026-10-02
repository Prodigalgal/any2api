package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.interop.InteropDatabase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderRuntimeBatchIntegrationTest {
    @Test
    void listPreservesPluginOrderCountsAndMissingInstallationWithoutPerPluginQueries() throws Exception {
        try (var database = new InteropDatabase()) {
            database.jdbc.sql("UPDATE providers SET installed=TRUE,enabled=TRUE WHERE id IN ('mimo','longcat')")
                .update();
            database.jdbc.sql("""
                INSERT INTO accounts(id,provider_id,external_id,status,enabled) VALUES
                    (gen_random_uuid(),'mimo','active','ACTIVE',TRUE),
                    (gen_random_uuid(),'mimo','disabled','DEGRADED',FALSE),
                    (gen_random_uuid(),'mimo','expired','EXPIRED',TRUE)
                """).update();
            database.jdbc.sql("""
                INSERT INTO models(id,provider_id,upstream_id,enabled) VALUES
                    ('mimo/test-enabled','mimo','test-enabled',TRUE),
                    ('mimo/test-disabled','mimo','test-disabled',FALSE)
                """).update();
            var registry = mock(ProviderRegistry.class);
            var modes = mock(ProviderTransportModeService.class);
            var plugins = List.of(plugin("mimo"), plugin("longcat"), plugin("future_provider"));
            when(registry.plugins()).thenReturn(plugins);
            for (var plugin : plugins) {
                when(registry.requirePlugin(plugin.manifest().id())).thenReturn(plugin);
                when(modes.view(plugin)).thenReturn(new ProviderTransportModeService.ModeView(
                    ProviderTransportMode.AUTO, ProviderTransportMode.API, ProviderTransportMode.RUNTIME,
                    Set.of(ProviderTransportMode.API, ProviderTransportMode.RUNTIME)));
            }
            var jdbc = spy(database.jdbc);
            var service = new ProviderRuntimeService(registry, mock(ProviderInstallationCatalog.class),
                new PostgresAdvisoryLocks(jdbc), jdbc, mock(ModelCatalogCache.class), modes);
            var views = service.list();

            assertThat(views).extracting(ProviderRuntimeService.ProviderRuntimeView::id)
                .containsExactly("mimo", "longcat", "future_provider");
            assertThat(views.getFirst().installed()).isTrue();
            assertThat(views.getFirst().accountCount()).isEqualTo(3);
            assertThat(views.getFirst().enabledAccountCount()).isEqualTo(1);
            assertThat(views.getFirst().modelCount()).isEqualTo(1);
            assertThat(views.getFirst().requestedTransportMode()).isEqualTo(ProviderTransportMode.AUTO);
            assertThat(views.getLast().installed()).isFalse();
            assertThat(views.getLast().modelCount()).isZero();
            assertThat(views.getLast().supportedTransportModes())
                .containsExactlyInAnyOrder(ProviderTransportMode.API, ProviderTransportMode.RUNTIME);
            verify(jdbc, times(1)).sql(anyString());
        }
    }

    private InferenceProvider plugin(String id) {
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(id, id, "test", "1", List.of(), Map.of(), true));
        return provider;
    }
}
