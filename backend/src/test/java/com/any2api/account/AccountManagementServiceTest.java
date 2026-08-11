package com.any2api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountManagementService.ImportCommand;
import com.any2api.credential.CredentialVault;
import com.any2api.credential.DecryptedCredential;
import com.any2api.credential.CredentialSummary;
import com.any2api.lifecycle.LifecycleScheduleService;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import com.any2api.provider.xai_identity.XaiAccountDerivationPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class AccountManagementServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameEmailAcrossProvidersCreatesIndependentAccountsAndCredentials() {
        var repository = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var stored = new ArrayList<AccountEntity>();
        var byIdentity = new HashMap<String, AccountEntity>();
        when(repository.findByProviderIdAndExternalId(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(byIdentity.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
        when(repository.save(any())).thenAnswer(invocation -> {
            AccountEntity account = invocation.getArgument(0);
            byIdentity.put(account.getProviderId() + ":" + account.getExternalId(), account);
            stored.add(account);
            return account;
        });
        when(vault.store(any(), any(), any(), any())).thenAnswer(invocation ->
            new DecryptedCredential("provider-session", 1, invocation.getArgument(3),
                invocation.getArgument(2)));
        var providers = ProviderRegistry.allEnabled(
            List.of(provider("alpha"), provider("beta")));
        var schedules = mock(LifecycleScheduleService.class);
        var service = new AccountManagementService(repository, vault, providers, schedules, List.of());
        var credential = mapper.createObjectNode().put("token", "secret");

        var first = service.importAccount(command("alpha", "upstream-a", credential));
        var second = service.importAccount(command("beta", "upstream-b", credential));

        assertThat(first.account().email()).isEqualTo("same@example.com");
        assertThat(second.account().email()).isEqualTo("same@example.com");
        assertThat(first.account().id()).isNotEqualTo(second.account().id());
        assertThat(stored).extracting(AccountEntity::getProviderId)
            .containsExactly("alpha", "beta");
        verify(vault).store(any(), eq("alpha"), any(), any());
        verify(vault).store(any(), eq("beta"), any(), any());
        verify(schedules).scheduleInitialProbe(first.account().id(), "alpha");
        verify(schedules).scheduleInitialProbe(second.account().id(), "beta");
    }

    @Test
    void registrationImportRejectsAnExistingProviderIdentityWithoutUpdatingIt() {
        var repository = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var existing = AccountEntity.create(
            "alpha", "upstream-a", "original@example.com", null, Map.of());
        when(repository.findByProviderIdAndExternalId("alpha", "upstream-a"))
            .thenReturn(Optional.of(existing));
        var service = new AccountManagementService(
            repository,
            vault,
            ProviderRegistry.allEnabled(List.of(provider("alpha"))),
            mock(LifecycleScheduleService.class),
            List.of());

        assertThatThrownBy(() -> service.importNewAccount(command(
                "alpha", "upstream-a", mapper.createObjectNode().put("token", "new"))))
            .isInstanceOf(DuplicateAccountException.class);

        verify(repository, never()).save(any());
        verify(vault, never()).store(any(), any(), any(), any());
        assertThat(existing.getEmail()).isEqualTo("original@example.com");
    }

    @Test
    void pendingAccountSchedulesReauthenticationWithoutEnteringKeepalive() {
        var repository = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        when(repository.findByProviderIdAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vault.store(any(), any(), any(), any())).thenAnswer(invocation ->
            new DecryptedCredential("provider-session", 1, invocation.getArgument(3),
                invocation.getArgument(2)));
        var schedules = mock(LifecycleScheduleService.class);
        var service = new AccountManagementService(
            repository, vault, ProviderRegistry.allEnabled(List.of(provider("grok"))),
            schedules, List.of());
        var credential = mapper.createObjectNode()
            .put("sso", "session-only")
            .put("auth_stage", "registered_pending_auth");

        var imported = service.importAccount(new ImportCommand(
            "grok", "pending-account", "same@example.com", null, null,
            Map.of(), null, null, null, AccountStatus.PENDING, false, credential, true));

        assertThat(imported.account().status()).isEqualTo(AccountStatus.PENDING);
        assertThat(imported.account().enabled()).isFalse();
        verify(schedules).scheduleReauthentication(imported.account().id(), "grok");
        verify(schedules, never()).scheduleInitialProbe(imported.account().id(), "grok");
    }

    @Test
    void accountDetailIncludesOnlyTheCredentialSummary() {
        var repository = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var account = AccountEntity.create(
            "qwen", "upstream-account", "same@example.com", null,
            Map.of("inference_probe_status", "READY"));
        var updatedAt = Instant.parse("2026-08-04T00:00:00Z");
        var summary = new CredentialSummary(
            true, "provider-session", 3, updatedAt.plusSeconds(3600), updatedAt);
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
        when(vault.summary(account, "qwen")).thenReturn(summary);
        var service = new AccountManagementService(
            repository,
            vault,
            ProviderRegistry.allEnabled(List.of(provider("qwen"))),
            mock(LifecycleScheduleService.class),
            List.of());

        var detail = service.detail(account.getId());

        assertThat(detail.account().id()).isEqualTo(account.getId());
        assertThat(detail.account().metadata()).containsEntry("inference_probe_status", "READY");
        assertThat(detail.credential()).isEqualTo(summary);
        verify(vault, never()).read(any(), any());
    }

    @Test
    void grokSsoCreatesDisabledIndependentWebAndConsoleAccounts() {
        var repository = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var stored = new HashMap<String, AccountEntity>();
        when(repository.findByProviderIdAndExternalId(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(stored.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
        when(repository.save(any())).thenAnswer(invocation -> {
            AccountEntity account = invocation.getArgument(0);
            stored.put(account.getProviderId() + ":" + account.getExternalId(), account);
            return account;
        });
        when(vault.store(any(), any(), any(), any())).thenAnswer(invocation ->
            new DecryptedCredential("provider-session", 1, invocation.getArgument(3),
                invocation.getArgument(2)));
        var providers = ProviderRegistry.allEnabled(List.of(
            provider("grok"), provider("grok_web"), provider("grok_console")));
        var service = new AccountManagementService(repository, vault, providers,
            mock(LifecycleScheduleService.class),
            List.of(new XaiAccountDerivationPolicy(mapper)));

        var result = service.importAccount(command("grok", "xai-user", mapper.createObjectNode()
            .put("access_token", "build-token").put("sso", "shared-session")));

        assertThat(result.account().metadata()).containsKey("identity_group_id");
        assertThat(stored).containsKeys(
            "grok:xai-user", "grok_web:xai-user", "grok_console:xai-user");
        assertThat(stored.get("grok_web:xai-user").getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(stored.get("grok_web:xai-user").isEnabled()).isFalse();
        assertThat(stored.get("grok_console:xai-user").getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(stored.get("grok_console:xai-user").isEnabled()).isFalse();
        verify(vault).store(any(), eq("grok_web"), any(), any());
        verify(vault).store(any(), eq("grok_console"), any(), any());
    }

    @Test
    void accountSearchReturnsAStableLightweightPage() {
        var repository = mock(AccountRepository.class);
        var account = AccountEntity.create(
            "alpha", "upstream-a", "same@example.com", null, Map.of("large", "metadata"));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(account),
                org.springframework.data.domain.PageRequest.of(1, 50), 2646));
        var service = new AccountManagementService(
            repository,
            mock(CredentialVault.class),
            ProviderRegistry.allEnabled(List.of(provider("alpha"))),
            mock(LifecycleScheduleService.class),
            List.of());

        var result = service.search(new AccountSearchQuery(
            " alpha ", AccountStatus.ACTIVE, true, " same ",
            AccountSearchQuery.Expiry.VALID, 1, 50));

        assertThat(result.totalElements()).isEqualTo(2646);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.providerId()).isEqualTo("alpha");
            assertThat(item.externalId()).isEqualTo("upstream-a");
        });
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void accountSearchRejectsUnboundedPagesAndKeywords() {
        assertThatThrownBy(() -> new AccountSearchQuery(
            null, null, null, "x".repeat(121), AccountSearchQuery.Expiry.ANY, 0, 25))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountSearchQuery(
            null, null, null, null, AccountSearchQuery.Expiry.ANY, 0, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ImportCommand command(String providerId, String externalId,
                                  tools.jackson.databind.JsonNode credential) {
        return new ImportCommand(providerId, externalId, "same@example.com", null, null,
            Map.of(), null, null, null, null, null, credential, true);
    }

    private InferenceProvider provider(String id) {
        var capabilities = Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE,
                ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE);
        return new InferenceProvider() {
            @Override public ProviderManifest manifest() {
                return new ProviderManifest(
                    id, id, "1", "1", List.of(), capabilities, true);
            }
            @Override public Flux<CanonicalEvent> generate(
                CanonicalRequest request, ProviderExecutionContext context,
                LeasedProviderAccount account
            ) { return Flux.empty(); }
            @Override public ProviderFailure classify(Throwable error) {
                return new ProviderFailure("test", "test", false, Map.of());
            }
        };
    }
}
