package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.any2api.interop.InteropDatabase;
import com.any2api.provider.ProviderTransportMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeyGrantBatchIntegrationTest {
    @Test
    void batchPermissionsRemainIsolatedAndMissingPermissionsFailClosed() throws Exception {
        try (var database = new InteropDatabase()) {
            var first = key(database, "selected", ProviderTransportMode.API);
            var second = key(database, "all", ProviderTransportMode.RUNTIME);
            var empty = key(database, "no-permissions", ProviderTransportMode.AUTO);
            database.jdbc.sql("""
                INSERT INTO models(id,provider_id,upstream_id)
                VALUES('mimo/selected-model','mimo','selected-model')
                """).update();
            var setup = new ApiKeyGrantStore(database.jdbc);
            setup.replace(first.getId(), Map.of("mimo",
                ApiKeyProviderScope.selectedModels("mimo", Set.of("selected-model"))),
                Set.of(ApiKeyProtocol.RESPONSES), Set.of(ApiKeyFeature.TOOL_CALLING));
            setup.replace(second.getId(), Map.of("longcat", ApiKeyProviderScope.allModels("longcat")),
                Set.of(ApiKeyProtocol.CHAT_COMPLETIONS), Set.of(ApiKeyFeature.FILE_UPLOADS));
            var jdbc = spy(database.jdbc);
            var grants = new ApiKeyGrantStore(jdbc).readAll(List.of(first, second, empty));

            assertThat(grants.get(first.getId()).allowsModel("mimo", "selected-model")).isTrue();
            assertThat(grants.get(first.getId()).allowsModel("mimo", "another-model")).isFalse();
            assertThat(grants.get(first.getId()).allowsProvider("longcat")).isFalse();
            assertThat(grants.get(first.getId()).protocols()).containsExactly(ApiKeyProtocol.RESPONSES);
            assertThat(grants.get(first.getId()).features()).containsExactly(ApiKeyFeature.TOOL_CALLING);
            assertThat(grants.get(first.getId()).transportMode()).isEqualTo(ProviderTransportMode.API);
            assertThat(grants.get(first.getId()).expiresAt()).isEqualTo(first.getExpiresAt());
            assertThat(grants.get(second.getId()).allowsModel("longcat", "any-model")).isTrue();
            assertThat(grants.get(second.getId()).allowsProvider("mimo")).isFalse();
            assertThat(grants.get(empty.getId()).providerModels()).isEmpty();
            assertThat(grants.get(empty.getId()).protocols()).isEmpty();
            assertThat(grants.get(empty.getId()).features()).isEmpty();
            verify(jdbc, times(1)).sql(anyString());
        }
    }

    @Test
    void largeRootListUsesBoundedBatchesAndEmptyListDoesNotQuery() throws Exception {
        try (var database = new InteropDatabase()) {
            var jdbc = spy(database.jdbc);
            var store = new ApiKeyGrantStore(jdbc);
            assertThat(store.readAll(List.of())).isEmpty();
            var keys = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> ApiKeyEntity.create("key-" + index, "fixture", "unused", null))
                .toList();
            assertThat(store.readAll(keys)).hasSize(501).allSatisfy((id, grant) -> {
                assertThat(grant.keyId()).isEqualTo(id);
                assertThat(grant.providerModels()).isEmpty();
            });
            verify(jdbc, times(2)).sql(anyString());
        }
    }

    private ApiKeyEntity key(InteropDatabase database, String name, ProviderTransportMode mode) {
        var key = ApiKeyEntity.create(name, "fixture", name, Instant.now().plusSeconds(3600), mode);
        key.beforeInsert();
        database.jdbc.sql("INSERT INTO api_keys(id,name,prefix,key_hash) VALUES(:id,:name,'fixture',:name)")
            .param("id", key.getId()).param("name", name).update();
        return key;
    }
}
