package com.any2api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.coordination.AccountLease;
import com.any2api.coordination.AccountLeaseService;
import com.any2api.credential.CredentialVault;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AccountSelectionCredentialPatchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refusesToRebaseAStalePatchOntoANewerLogin() {
        var accounts = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var account = mock(AccountEntity.class);
        var leased = leased();
        when(accounts.findById(leased.accountId())).thenReturn(Optional.of(account));
        when(vault.storeIfVersion(eq(account), eq("alpha"), eq(7L), any(), any()))
            .thenThrow(new IllegalStateException("provider credential changed"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new AccountSelectionService(accounts, vault,
                mock(AccountLeaseService.class), mock(AccountModelCooldownStore.class), executor);
            StepVerifier.create(service.mergeCredentialPatch(
                    leased, mapper.createObjectNode().put("token", "old-session-rotation")))
                .expectErrorMessage("provider credential changed").verify();
            verify(vault, never()).read(any(), any());
            verify(vault).storeIfVersion(eq(account), eq("alpha"), eq(7L), any(), any());
        }
    }

    @Test
    void mergesThePatchIntoItsOriginalSnapshotWithoutMutatingIt() {
        var accounts = mock(AccountRepository.class);
        var vault = mock(CredentialVault.class);
        var account = mock(AccountEntity.class);
        var leased = leased();
        when(accounts.findById(leased.accountId())).thenReturn(Optional.of(account));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new AccountSelectionService(accounts, vault,
                mock(AccountLeaseService.class), mock(AccountModelCooldownStore.class), executor);
            StepVerifier.create(service.mergeCredentialPatch(
                    leased, mapper.createObjectNode().put("token", "rotated")))
                .expectNext(true).verifyComplete();
            var payload = ArgumentCaptor.forClass(JsonNode.class);
            verify(vault).storeIfVersion(eq(account), eq("alpha"), eq(7L),
                payload.capture(), eq(leased.credentialExpiresAt()));
            assertThat(payload.getValue().path("token").asText()).isEqualTo("rotated");
            assertThat(payload.getValue().path("device").asText()).isEqualTo("stable");
            assertThat(leased.credential().path("token").asText()).isEqualTo("original");
        }
    }

    private LeasedProviderAccount leased() {
        var id = UUID.randomUUID();
        var expiry = Instant.now().plusSeconds(3600);
        return new LeasedProviderAccount(id, "alpha", "external", "", 7, expiry,
            mapper.createObjectNode().put("token", "original").put("device", "stable"),
            Map.of(), new AccountLease("alpha", id, "owner", 1, expiry));
    }
}
