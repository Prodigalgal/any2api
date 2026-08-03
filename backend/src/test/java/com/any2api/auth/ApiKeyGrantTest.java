package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyGrantTest {
    @Test
    void providerScopesCanAllowAllOrSelectedModels() {
        var grant = new ApiKeyGrant(
            UUID.randomUUID(), "client",
            Map.of(
                "alpha", ApiKeyProviderScope.allModels("alpha"),
                "beta", ApiKeyProviderScope.selectedModels("beta", Set.of("model-b"))),
            Set.of(ApiKeyProtocol.CHAT_COMPLETIONS), null, false);

        assertThat(grant.allowsProtocol(ApiKeyProtocol.CHAT_COMPLETIONS)).isTrue();
        assertThat(grant.allowsProtocol(ApiKeyProtocol.RESPONSES)).isFalse();
        assertThat(grant.allowsModel("alpha", "any-model")).isTrue();
        assertThat(grant.allowsModel("beta", "model-b")).isTrue();
        assertThat(grant.allowsModel("beta", "other-model")).isFalse();
        assertThat(grant.allowsModel("gamma", "model-b")).isFalse();
    }
}
