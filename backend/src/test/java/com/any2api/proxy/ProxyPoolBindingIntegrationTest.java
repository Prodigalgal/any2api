package com.any2api.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.any2api.config.Any2ApiProperties;
import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.credential.SecretCipher;
import com.any2api.interop.InteropDatabase;
import com.any2api.provider.ProviderInstallationCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProxyPoolBindingIntegrationTest {
    @Test
    void rebindingExpandsTrafficScopesAndAnUpdateWithoutSourcePreservesTheNodes() throws Exception {
        try (var database = new InteropDatabase()) {
            var properties = new Any2ApiProperties();
            properties.getSecurity().setCredentialMasterKey(java.util.Base64.getUrlEncoder().encodeToString(new byte[32]));
            var service = new ProxyPoolService(database.jdbc, new PostgresAdvisoryLocks(database.jdbc),
                mock(ProviderInstallationCatalog.class), new SecretCipher(properties), new ObjectMapper());
            var source = "http://127.0.0.1:19001\nhttp://127.0.0.1:19002\nhttp://127.0.0.1:19003\nhttp://127.0.0.1:19004";
            var pool = service.create(new ProxyPoolService.SaveCommand("fixture", "NODE_LIST", true,
                source, List.of("mimo"), Map.of("mimo", List.of("REGISTRATION"))));
            assertThat(service.runtimeForProvider("mimo", ProxyTrafficScope.INFERENCE)).isEmpty();

            service.bindProvider("mimo", pool.id());

            var before = service.runtimeForProvider("mimo", ProxyTrafficScope.INFERENCE).orElseThrow();
            assertThat(service.runtimeForProvider("mimo", ProxyTrafficScope.LIFECYCLE)).isPresent();
            assertThat(service.runtimeForProvider("mimo", ProxyTrafficScope.REGISTRATION)).isPresent();
            var updated = service.update(pool.id(), new ProxyPoolService.SaveCommand("fixture", "NODE_LIST", true,
                null, List.of("mimo"), Map.of("mimo", List.of("INFERENCE", "LIFECYCLE", "REGISTRATION"))));
            assertThat(updated.nodeCount()).isEqualTo(4);
            assertThat(updated.bindingScopes().get("mimo")).containsExactlyInAnyOrder("INFERENCE", "LIFECYCLE", "REGISTRATION");
            assertThat(service.runtimeForProvider("mimo", ProxyTrafficScope.INFERENCE).orElseThrow()).isEqualTo(before);
            assertThat(database.jdbc.sql("SELECT enabled FROM providers WHERE id='qwen'")
                .query(Boolean.class).single()).isFalse();
        }
    }
}
