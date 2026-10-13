package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class AccountProbeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void repeatedManagementCheckReturnsHistoryWithoutGenerationOrReactivation() {
        try (var fixture = fixture(AccountStatus.PENDING, false)) {
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class), any(String.class)))
                .thenReturn(Mono.just(InferenceReadinessProbe.Result.ready("alpha-top")));
            var first = fixture.service().probe(fixture.account().getId()).block();
            assertThat(first.performed()).isTrue();
            fixture.account().updateState(AccountStatus.DISABLED, false);

            var repeated = fixture.service().probe(fixture.account().getId(), "claude-sonnet").block();

            assertThat(repeated.performed()).isFalse();
            assertThat(repeated.ready()).isTrue();
            assertThat(repeated.account().status()).isEqualTo(AccountStatus.DISABLED);
            assertThat(repeated.account().enabled()).isFalse();
            assertThat(repeated.completedAt()).isEqualTo(first.completedAt());
            org.mockito.Mockito.verify(fixture.accounts(), org.mockito.Mockito.times(1))
                .acquire(fixture.account().getId());
            org.mockito.Mockito.verify(fixture.readiness(), org.mockito.Mockito.times(1))
                .probe(eq(fixture.leased()), any(Duration.class), any(String.class));
        }
    }

    @Test
    void failedRealtimeProbeDoesNotPreemptivelyDisableOrPendTheAccount() {
        try (var fixture = fixture(AccountStatus.ACTIVE, true)) {
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class), any(String.class)))
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
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class), any(String.class)))
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

    @Test
    void probeUsesTheProviderDeclaredRecoveryTimeout() {
        try (var fixture = fixture(
                AccountStatus.ACTIVE, true, "extended", Duration.ofSeconds(90))) {
            when(fixture.readiness().probe(
                    eq(fixture.leased()), eq(Duration.ofSeconds(90)), eq("extended-top")))
                .thenReturn(Mono.just(InferenceReadinessProbe.Result.ready("extended-top")));

            var result = fixture.service().probe(fixture.account().getId()).block();

            assertThat(result).isNotNull();
            assertThat(result.ready()).isTrue();
            verify(fixture.readiness()).probe(
                fixture.leased(), Duration.ofSeconds(90), "extended-top");
        }
    }

    @Test
    void cancelledRealtimeProbeClosesItsRunningOperationEvent() throws Exception {
        try (var fixture = fixture(AccountStatus.ACTIVE, true)) {
            var subscribed = new CountDownLatch(1);
            when(fixture.readiness().probe(eq(fixture.leased()), any(Duration.class), any(String.class)))
                .thenReturn(Mono.<InferenceReadinessProbe.Result>never()
                    .doOnSubscribe(ignored -> subscribed.countDown()));

            var subscription = fixture.service().probe(fixture.account().getId()).subscribe();
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();

            verify(fixture.observability(), timeout(1_000)).fail(
                any(OperationEventService.Started.class),
                eq("request_cancelled"),
                eq("client_cancelled"),
                eq("account probe request was cancelled"));
            verify(fixture.accounts(), timeout(1_000)).release(fixture.leased());
        }
    }

    private Fixture fixture(AccountStatus status, boolean enabled) {
        return fixture(status, enabled, "alpha", Duration.ofSeconds(30));
    }

    private Fixture fixture(
        AccountStatus status,
        boolean enabled,
        String providerId,
        Duration accountProbeTimeout
    ) {
        var repository = mock(AccountRepository.class);
        var accounts = mock(AccountSelectionService.class);
        var failures = mock(ProviderFailureDisposition.class);
        var readiness = mock(InferenceReadinessProbe.class);
        var observability = mock(OperationEventService.class);
        var account = AccountEntity.create(
            providerId, "external", "same@example.com", null, Map.of());
        account.updateState(status, enabled);
        var lease = new AccountLease(
            providerId, account.getId(), "owner", 1, Instant.now().plusSeconds(60));
        var leased = new LeasedProviderAccount(
            account.getId(), providerId, "external", "same@example.com", 1, null,
            mapper.createObjectNode(), Map.of(), lease);
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
        when(repository.save(account)).thenReturn(account);
        when(accounts.acquire(account.getId())).thenReturn(Mono.just(leased));
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any())).thenReturn(Mono.just(false));
        var started = new OperationEventService.Started(
            java.util.UUID.randomUUID(), "correlation", "LIFECYCLE", providerId, "probe",
            "ACCOUNT", account.getId().toString(), 1, Instant.now());
        when(observability.start(eq("LIFECYCLE"), eq(providerId), eq("probe"), any()))
            .thenReturn(started);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenReturn(mock(TransactionStatus.class));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var modelCatalog = mock(com.any2api.provider.ModelCatalogCache.class);
        var records = new java.util.concurrent.ConcurrentHashMap<java.util.UUID, InitialInferenceCheckRepository.Check>();
        var checksRepository = mock(InitialInferenceCheckRepository.class);
        when(checksRepository.find(any())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(checksRepository.claim(any(), any())).thenAnswer(call -> {
            java.util.UUID id = call.getArgument(0);
            var check = new InitialInferenceCheckRepository.Check(id, java.util.UUID.randomUUID(), "RUNNING", "INITIAL",
                call.getArgument(1), "", "", 0, Instant.now(), null);
            var previous = records.putIfAbsent(id, check);
            return new InitialInferenceCheckRepository.Claim(previous == null ? check : previous, previous == null);
        });
        when(checksRepository.complete(any(), any())).thenAnswer(call -> {
            InitialInferenceCheckRepository.Check check = call.getArgument(0);
            InferenceReadinessProbe.Result result = call.getArgument(1);
            var completed = new InitialInferenceCheckRepository.Check(check.accountId(), check.claimToken(),
                result.ready() ? "READY" : "FAILED", "INITIAL", result.model(), result.errorClass(), result.output(),
                result.durationMs(), check.startedAt(), Instant.now());
            records.put(check.accountId(), completed);
            return completed;
        });
        when(readiness.resolveModel(eq(providerId), any())).thenReturn(providerId + "-top");
        var initialChecks = new InitialInferenceCheckService(checksRepository, executor);
        var service = new AccountProbeService(
            repository,
            accounts,
            ProviderRegistry.allEnabled(
                List.of(provider(providerId, accountProbeTimeout))),
            failures,
            readiness,
            observability,
            transactionManager,
            executor,
            modelCatalog,
            initialChecks);
        return new Fixture(
            service, repository, accounts, failures, readiness, observability, checksRepository,
            account, leased, executor);
    }

    private InferenceProvider provider(String providerId, Duration accountProbeTimeout) {
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest(
            providerId, providerId, "test", "1", List.of(providerId + "-top"),
            Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE),
            true));
        when(provider.protocolContract())
            .thenCallRealMethod();
        when(provider.accountProbeTimeout()).thenReturn(accountProbeTimeout);
        return provider;
    }

    private record Fixture(
        AccountProbeService service,
        AccountRepository repository,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures,
        InferenceReadinessProbe readiness,
        OperationEventService observability,
        InitialInferenceCheckRepository checksRepository,
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
