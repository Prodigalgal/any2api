package com.any2api.provider.xai_identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.account.LeasedProviderAccount;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class XaiAccountRecoveryPolicyTest {
    @Test
    void resolvesDerivedChannelToSourceIdentity() {
        var accounts = mock(AccountRepository.class);
        var source = AccountEntity.create(
            "grok", "source", "source@example.test", null,
            Map.of("identity_group_id", "group-1"));
        when(accounts.findByProviderIdAndIdentityGroup("grok", "group-1"))
            .thenReturn(Optional.of(source));
        var failed = new LeasedProviderAccount(
            java.util.UUID.randomUUID(), "grok_console", "derived", null,
            1, null, JsonNodeFactory.instance.objectNode(),
            Map.of("identity_group_id", "group-1"), null);

        var target = new XaiAccountRecoveryPolicy(accounts).resolve(
            failed, "permission_or_egress_denied");

        assertThat(target).isPresent();
        assertThat(target.orElseThrow().accountId()).isEqualTo(source.getId());
        assertThat(target.orElseThrow().providerId()).isEqualTo("grok");
        assertThat(target.orElseThrow().metadataPatch())
            .containsEntry("xai_force_sso_refresh", true)
            .containsEntry("xai_recovery_source", "grok_console");
    }

    @Test
    void rejectsFailuresThatDoNotIndicateSessionRecovery() {
        var accounts = mock(AccountRepository.class);
        var failed = new LeasedProviderAccount(
            java.util.UUID.randomUUID(), "grok_web", "derived", null,
            1, null, JsonNodeFactory.instance.objectNode(),
            Map.of("identity_group_id", "group-1"), null);

        var target = new XaiAccountRecoveryPolicy(accounts).resolve(
            failed, "rate_limited");

        assertThat(target).isEmpty();
    }
}
