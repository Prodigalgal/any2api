package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountRecoveryServiceTest {
    @Test
    void schedulesDirectReauthenticationWhenProviderSupportsIt() {
        var accounts = mock(AccountRepository.class);
        var providers = mock(ProviderRegistry.class);
        var schedules = mock(LifecycleScheduleService.class);
        var provider = provider("qwen", Map.of(
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE));
        var failed = leased("qwen", Map.of());
        when(providers.require("qwen")).thenReturn(provider);

        var scheduled = new AccountRecoveryService(
            accounts, providers, schedules, List.of()).schedule(failed);

        assertThat(scheduled).isTrue();
        verify(schedules).scheduleReauthentication(failed.accountId(), "qwen");
        verify(accounts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesUnsupportedDerivedProviderToItsRecoveryTarget() {
        var accounts = mock(AccountRepository.class);
        var providers = mock(ProviderRegistry.class);
        var schedules = mock(LifecycleScheduleService.class);
        var policy = mock(AccountRecoveryPolicy.class);
        var failed = leased("grok_web", Map.of("identity_group_id", "group-1"));
        var source = AccountEntity.create(
            "grok", "source", "source@example.test", null,
            Map.of("identity_group_id", "group-1"));
        var provider = provider("grok_web", Map.of());
        var target = new AccountRecoveryPolicy.RecoveryTarget(
            source.getId(), "grok", Map.of("xai_force_sso_refresh", true));
        when(providers.require("grok_web")).thenReturn(provider);
        when(policy.resolve(failed)).thenReturn(Optional.of(target));
        when(accounts.findById(source.getId())).thenReturn(Optional.of(source));

        var scheduled = new AccountRecoveryService(
            accounts, providers, schedules, List.of(policy)).schedule(failed);

        assertThat(scheduled).isTrue();
        assertThat(source.getMetadata()).containsEntry("xai_force_sso_refresh", true);
        verify(accounts).save(source);
        verify(schedules).scheduleReauthentication(source.getId(), "grok");
        verify(schedules, never()).scheduleReauthentication(failed.accountId(), "grok_web");
    }

    @Test
    void declinesRecoveryWhenUnsupportedProviderHasNoPolicy() {
        var accounts = mock(AccountRepository.class);
        var providers = mock(ProviderRegistry.class);
        var schedules = mock(LifecycleScheduleService.class);
        var failed = leased("grok_console", Map.of());
        var provider = provider("grok_console", Map.of());
        when(providers.require("grok_console"))
            .thenReturn(provider);

        var scheduled = new AccountRecoveryService(
            accounts, providers, schedules, List.of()).schedule(failed);

        assertThat(scheduled).isFalse();
        verify(schedules, never()).scheduleReauthentication(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    private static InferenceProvider provider(
        String providerId,
        Map<ProviderCapability, SupportLevel> capabilities
    ) {
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(
            providerId, providerId, "test", "1", List.of("model"), capabilities, true));
        return provider;
    }

    private static LeasedProviderAccount leased(
        String providerId,
        Map<String, Object> metadata
    ) {
        return new LeasedProviderAccount(
            java.util.UUID.randomUUID(), providerId, "external", "account@example.test",
            1, null, tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            metadata, null);
    }
}
