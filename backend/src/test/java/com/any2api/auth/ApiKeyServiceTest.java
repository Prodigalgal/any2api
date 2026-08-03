package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApiKeyServiceTest {
    @Test
    void createPersistsOnlyHashAndNormalizedGrants() {
        var keys = mock(ApiKeyRepository.class);
        var grants = mock(ApiKeyGrantStore.class);
        var authenticator = mock(ApiKeyAuthenticator.class);
        var providers = mock(ProviderRegistry.class);
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(
            "mimo", "MiMo", "1", "1", List.of(), Map.of(), true));
        when(providers.requirePlugin("mimo")).thenReturn(provider);
        when(keys.saveAndFlush(any())).thenAnswer(invocation -> {
            var key = invocation.getArgument(0, ApiKeyEntity.class);
            key.beforeInsert();
            return key;
        });
        when(grants.read(any())).thenAnswer(invocation -> {
            var key = invocation.getArgument(0, ApiKeyEntity.class);
            return new ApiKeyGrant(
                key.getId(), key.getName(),
                Map.of("mimo", ApiKeyProviderScope.allModels("mimo")),
                Set.of(ApiKeyProtocol.CHAT_COMPLETIONS), key.getExpiresAt(), false);
        });
        var service = new ApiKeyService(
            keys, grants, authenticator, providers, mock(JdbcClient.class));

        var created = service.create(new ApiKeyService.CreateCommand(
            "desktop client", Map.of("mimo", List.of()),
            Set.of(ApiKeyProtocol.CHAT_COMPLETIONS), null));

        var entity = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(keys).saveAndFlush(entity.capture());
        assertThat(created.secret()).startsWith("sk-a2a-").hasSizeGreaterThan(40);
        assertThat(entity.getValue().getKeyHash())
            .isEqualTo(ApiKeyAuthenticator.hash(created.secret()))
            .doesNotContain(created.secret());
        assertThat(entity.getValue().getPrefix()).isEqualTo(created.key().prefix());
        verify(grants).replace(
            entity.getValue().getId(),
            Map.of("mimo", ApiKeyProviderScope.allModels("mimo")),
            Set.of(ApiKeyProtocol.CHAT_COMPLETIONS));
    }
}
