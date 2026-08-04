package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.account.AccountSelectionService;
import com.any2api.account.AccountStatus;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.observability.OperationEventService;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderFailureDisposition;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class AccountProbeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void failedRealtimeProbeDoesNotPreemptivelyDisableOrPendTheAccount() {
        try (var fixture = fixture(AccountStatus.ACTIVE, true)) {
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class)))
                .thenAnswer(ignored -> {
                    assertThat(fixture.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
                    assertThat(fixture.account().isEnabled()).isTrue();
                    return Mono.just(InferenceReadinessProbe.Result.failed(
                        "alpha-top", "empty_model_response"));
                });
            when(fixture.failures().report(
                eq(fixture.leased()), eq("alpha-top"), any()))
                .thenReturn(Mono.empty());

            var result = fixture.service().probe(fixture.account().getId()).block();

            assertThat(result).isNotNull();
            assertThat(result.ready()).isFalse();
            assertThat(fixture.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(fixture.account().isEnabled()).isTrue();
            assertThat(fixture.account().getMetadata())
                .containsEntry("inference_probe_status", "FAILED")
                .containsEntry("inference_readiness_pending", false);
            verify(fixture.accounts()).release(fixture.leased());
            verify(fixture.observability()).fail(
                any(OperationEventService.Started.class),
                eq("empty_model_response"),
                eq("inference_probe"),
                any(String.class));
        }
    }

    @Test
    void successfulRealtimeProbeActivatesOnlyAfterReceivingModelOutput() {
        try (var fixture = fixture(AccountStatus.PENDING, false)) {
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class)))
                .thenReturn(Mono.just(InferenceReadinessProbe.Result.ready("alpha-top")));

            var result = fixture.service().probe(fixture.account().getId()).block();

            assertThat(result).isNotNull();
            assertThat(result.ready()).isTrue();
            assertThat(result.account().status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(result.account().enabled()).isTrue();
            assertThat(result.account().successCount()).isEqualTo(1);
            assertThat(fixture.account().getMetadata())
                .containsEntry("inference_probe_status", "READY")
                .containsEntry("inference_readiness_pending", false);
            verify(fixture.observability()).succeed(
                any(OperationEventService.Started.class), eq("inference_probe_ready"));
        }
    }

    private Fixture fixture(AccountStatus status, boolean enabled) {
        var repository = mock(AccountRepository.class);
        var accounts = mock(AccountSelectionService.class);
        var failures = mock(ProviderFailureDisposition.class);
        var readiness = mock(InferenceReadinessProbe.class);
        var observability = mock(OperationEventService.class);
        var account = AccountEntity.create(
            "alpha", "external", "same@example.com", null, Map.of());
        account.updateState(status, enabled);
        var lease = new AccountLease(
            "alpha", account.getId(), "owner", 1, Instant.now().plusSeconds(60));
        var leased = new LeasedProviderAccount(
            account.getId(), "alpha", "external", "same@example.com", 1, null,
            mapper.createObjectNode(), Map.of(), lease);
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
        when(repository.save(account)).thenReturn(account);
        when(accounts.acquire(account.getId())).thenReturn(Mono.just(leased));
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any())).thenReturn(Mono.just(false));
        var started = new OperationEventService.Started(
            java.util.UUID.randomUUID(), "correlation", "LIFECYCLE", "alpha", "probe",
            "ACCOUNT", account.getId().toString(), 1, Instant.now());
        when(observability.start(eq("LIFECYCLE"), eq("alpha"), eq("probe"), any()))
            .thenReturn(started);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenReturn(mock(TransactionStatus.class));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var service = new AccountProbeService(
            repository,
            accounts,
            new ProviderRegistry(List.of(provider())),
            failures,
            readiness,
            observability,
            transactionManager,
            executor);
        return new Fixture(
            service, repository, accounts, failures, readiness, observability,
            account, leased, executor);
    }

    private InferenceProvider provider() {
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(
            "alpha", "Alpha", "test", "1", List.of("alpha-top"),
            Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE),
            true));
        when(provider.protocolContract())
            .thenCallRealMethod();
        return provider;
    }

    private record Fixture(
        AccountProbeService service,
        AccountRepository repository,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures,
        InferenceReadinessProbe readiness,
        OperationEventService observability,
        AccountEntity account,
        LeasedProviderAccount leased,
        java.util.concurrent.ExecutorService executor
    ) implements AutoCloseable {
        @Override
        public void close() {
            executor.close();
        }
    }
}
