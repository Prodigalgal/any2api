package com.any2api.provider.xai_identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.credential.CredentialVault;
import com.any2api.credential.DecryptedCredential;
import com.any2api.lifecycle.LifecycleScheduleService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class XaiCredentialPropagationPolicyTest {
    @Test
    void replacesOnlySharedSessionFieldsAndSchedulesDerivedProbes() {
        var accounts = mock(AccountRepository.class);
        var credentials = mock(CredentialVault.class);
        var schedules = mock(LifecycleScheduleService.class);
        var mapper = new ObjectMapper();
        var source = AccountEntity.create(
            "grok", "source", "source@example.test", null,
            Map.of("identity_group_id", "group-1", "xai_force_sso_refresh", true));
        var web = AccountEntity.create(
            "grok_web", "web", null, null, Map.of("identity_group_id", "group-1"));
        var console = AccountEntity.create(
            "grok_console", "console", null, null, Map.of("identity_group_id", "group-1"));
        var current = mapper.createObjectNode()
            .put("sso", "old-sso")
            .put("channel_secret", "preserved");
        var recovered = mapper.createObjectNode()
            .put("sso", "new-sso")
            .put("access_token", "source-only-token");
        var expiresAt = Instant.parse("2026-08-18T00:00:00Z");
        when(accounts.findByProviderIdAndIdentityGroup("grok_web", "group-1"))
            .thenReturn(Optional.of(web));
        when(accounts.findByProviderIdAndIdentityGroup("grok_console", "group-1"))
            .thenReturn(Optional.of(console));
        when(credentials.read(web, "grok_web"))
            .thenReturn(new DecryptedCredential("provider-session", 1, null, current));
        when(credentials.read(console, "grok_console"))
            .thenReturn(new DecryptedCredential("provider-session", 1, null, current));

        new XaiCredentialPropagationPolicy(accounts, credentials, schedules)
            .propagate(source, recovered, expiresAt);

        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(credentials).store(eq(web), eq("grok_web"), payload.capture(), eq(expiresAt));
        verify(credentials).store(eq(console), eq("grok_console"), payload.capture(), eq(expiresAt));
        assertThat(payload.getAllValues()).allSatisfy(value -> {
            assertThat(value.path("sso").asText()).isEqualTo("new-sso");
            assertThat(value.path("sso-rw").asText()).isEqualTo("new-sso");
            assertThat(value.path("channel_secret").asText()).isEqualTo("preserved");
            assertThat(value.has("access_token")).isFalse();
        });
        verify(schedules).scheduleRecoveryProbe(
            web.getId(), "grok_web", Duration.ofMinutes(2));
        verify(schedules).scheduleRecoveryProbe(
            console.getId(), "grok_console", Duration.ofMinutes(2));
        assertThat(source.getMetadata()).containsEntry("xai_force_sso_refresh", false);
        verify(accounts).save(source);
    }
}
