package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountSelectionService;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.observability.InferenceTelemetryService;
import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.UsageNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class InferenceCoordinatorTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesMissingRequiredToolCallsBeforeOutput(boolean stream) {
        var accounts = mock(AccountSelectionService.class);
        var leasedAccounts = List.of(leased("alpha"), leased("alpha"), leased("alpha"));
        when(accounts.acquire(eq("alpha"), eq("model"), any())).thenReturn(
            Mono.just(leasedAccounts.get(0)), Mono.just(leasedAccounts.get(1)), Mono.just(leasedAccounts.get(2)));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportSuccess(leasedAccounts.get(2), "model")).thenReturn(Mono.empty());
        StepVerifier.create(coordinator(new RetryingProvider("tool_call_generation_failed", 2, false), accounts)
                .execute(request("alpha", stream)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(CanonicalEvent.Usage.class::isInstance)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();
        verify(accounts, times(3)).acquire(eq("alpha"), eq("model"), any());
        leasedAccounts.forEach(account -> verify(accounts).release(account));
        assertThat(ProviderRetryPolicy.standard().shouldRetry("tool_call_generation_failed", 1)).isTrue();
        assertThat(ProviderRetryPolicy.standard().shouldRetry("tool_call_generation_failed", 3)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacesRejectedCredentialsBeforeOutputAndExcludesEveryPreviousAccount(boolean stream) {
        var accounts = mock(AccountSelectionService.class);
        var leasedAccounts = List.of(leased("alpha"), leased("alpha"), leased("alpha"));
        var acquisitions = new AtomicInteger();
        when(accounts.acquire(eq("alpha"), eq("model"), any())).thenAnswer(invocation -> {
            int attempt = acquisitions.getAndIncrement();
            java.util.function.Predicate<ProviderAccountProfile> eligibility = invocation.getArgument(2);
            for (int previous = 0; previous < attempt; previous++) {
                assertThat(eligibility.test(new ProviderAccountProfile(
                    leasedAccounts.get(previous).accountId(), Map.of()))).isFalse();
            }
            assertThat(eligibility.test(new ProviderAccountProfile(
                leasedAccounts.get(attempt).accountId(), Map.of()))).isTrue();
            return Mono.just(leasedAccounts.get(attempt));
        });
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportAuthenticationFailure(any(), eq("empty"))).thenReturn(Mono.empty());
        when(accounts.reportSuccess(leasedAccounts.get(2), "model")).thenReturn(Mono.empty());
        var provider = new RetryingProvider("credential_rejected", 2, false);

        StepVerifier.create(coordinator(provider, accounts).execute(request("alpha", stream)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(CanonicalEvent.Usage.class::isInstance)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();

        verify(accounts, times(3)).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).reportAuthenticationFailure(leasedAccounts.get(0), "empty");
        verify(accounts).reportAuthenticationFailure(leasedAccounts.get(1), "empty");
        leasedAccounts.forEach(account -> verify(accounts).release(account));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsCredentialReplacementAtThreeAttempts(boolean stream) {
        var accounts = mock(AccountSelectionService.class);
        var leasedAccounts = List.of(leased("alpha"), leased("alpha"), leased("alpha"));
        when(accounts.acquire(eq("alpha"), eq("model"), any())).thenReturn(
            Mono.just(leasedAccounts.get(0)), Mono.just(leasedAccounts.get(1)),
            Mono.just(leasedAccounts.get(2)));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportAuthenticationFailure(any(), eq("empty"))).thenReturn(Mono.empty());

        StepVerifier.create(coordinator(new RetryingProvider("credential_rejected", 3, false), accounts)
                .execute(request("alpha", stream)))
            .expectNextMatches(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("credential_rejected"))
            .verifyComplete();

        verify(accounts, times(3)).acquire(eq("alpha"), eq("model"), any());
        leasedAccounts.forEach(account -> {
            verify(accounts).reportAuthenticationFailure(account, "empty");
            verify(accounts).release(account);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotReplaceRejectedCredentialsAfterMeaningfulOutput(boolean stream) {
        var accounts = mock(AccountSelectionService.class);
        var account = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any())).thenReturn(Mono.just(account));
        when(accounts.release(account)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(account), any())).thenReturn(Mono.just(false));
        when(accounts.reportAuthenticationFailure(account, "empty")).thenReturn(Mono.empty());

        StepVerifier.create(coordinator(new RetryingProvider("credential_rejected", 2, true), accounts)
                .execute(request("alpha", stream)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("credential_rejected"))
            .verifyComplete();

        verify(accounts).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).release(account);
    }

    @Test
    void appliesApiKeyTransportModeToTheInferencePlan() {
        var accounts = mock(AccountSelectionService.class);
        var transportModes = mock(ProviderTransportModeService.class);
        when(transportModes.plan(any(), eq(ProviderTransportMode.API))).thenReturn(
            new ProviderTransportModeService.TransportPlan(
                ProviderTransportMode.API, ProviderTransportMode.API, null));
        var coordinator = coordinator(new TestProvider(false), accounts, transportModes);

        coordinator.execute(
            request("alpha"), UUID.randomUUID(), ProviderTransportMode.API);

        verify(transportModes).plan(any(), eq(ProviderTransportMode.API));
    }

    @Test
    void releasesPreleasedRandomAccountWhenProviderValidationFails() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        var provider = new TestProvider(true);
        var coordinator = coordinator(provider, accounts);

        StepVerifier.create(coordinator.execute(request("alpha"), leased))
            .expectErrorMatches(error -> error instanceof com.any2api.protocol.OpenAiRequestException invalid
                && invalid.type().equals("invalid_request_error")
                && invalid.getMessage().contains("rejected"))
            .verify();

        verify(accounts).release(leased);
    }

    @Test
    void convertsProviderPreflightErrorsBeforeAcquiringAnAccount() {
        var accounts = mock(AccountSelectionService.class);
        var coordinator = coordinator(new TestProvider(true), accounts);

        StepVerifier.create(coordinator.execute(request("alpha")))
            .expectErrorMatches(error -> error instanceof com.any2api.protocol.OpenAiRequestException invalid
                && invalid.type().equals("invalid_request_error")
                && invalid.parameter().equals("request")
                && invalid.getMessage().contains("rejected"))
            .verify();

        verify(accounts, never()).acquire(anyString(), anyString(), any());
    }

    @Test
    void doesNotReportSuccessWhenAProviderEmitsCanonicalFailure() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any(tools.jackson.databind.JsonNode.class)))
            .thenReturn(Mono.just(true));
        var provider = new TestProvider(false, new CanonicalEvent.Failed(
            1, "request-id", 1, "upstream_timeout", "timeout", Map.of()));
        var coordinator = coordinator(provider, accounts);

        StepVerifier.create(coordinator.execute(request("alpha"), leased))
            .expectNextMatches(CanonicalEvent.Failed.class::isInstance)
            .verifyComplete();

        verify(accounts, never()).reportSuccess(leased, "model");
        verify(accounts).release(leased);
    }

    @Test
    void retriesADeclaredFailureBeforeAnyClientVisibleEvent() {
        var accounts = mock(AccountSelectionService.class);
        var first = leased("alpha");
        var second = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any()))
            .thenReturn(Mono.just(first), Mono.just(second));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportModelCooldown(
            first, "model", "empty", java.time.Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());
        when(accounts.reportSuccess(second, "model")).thenReturn(Mono.empty());
        var coordinator = coordinator(new RetryingProvider(), accounts);

        StepVerifier.create(coordinator.execute(request("alpha")))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(event -> event instanceof CanonicalEvent.Usage usage
                && usage.source() == com.any2api.protocol.UsageSource.ESTIMATED)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();

        verify(accounts, times(2)).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).reportModelCooldown(
            first, "model", "empty", java.time.Duration.ofMinutes(5));
        verify(accounts).reportSuccess(second, "model");
        verify(accounts).release(first);
        verify(accounts).release(second);
    }

    @Test
    void retriesAfterResponseStartedWhenNoMeaningfulOutputWasVisible() {
        var accounts = mock(AccountSelectionService.class);
        var first = leased("alpha");
        var second = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any()))
            .thenReturn(Mono.just(first), Mono.just(second));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportModelCooldown(
            first, "model", "empty", java.time.Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());
        when(accounts.reportSuccess(second, "model")).thenReturn(Mono.empty());
        var coordinator = coordinator(new RetryingProvider(true), accounts);

        StepVerifier.create(coordinator.execute(request("alpha", true)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(CanonicalEvent.Usage.class::isInstance)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();

        verify(accounts, times(2)).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).release(first);
        verify(accounts).release(second);
    }

    @Test
    void recordsAccountSuccessBeforeAStreamingClientCancelsAfterCompleted() {
        var accounts = mock(AccountSelectionService.class);
        var first = leased("alpha");
        var second = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any()))
            .thenReturn(Mono.just(first), Mono.just(second));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportModelCooldown(
            first, "model", "empty", java.time.Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());
        when(accounts.reportSuccess(second, "model")).thenReturn(Mono.empty());
        var coordinator = coordinator(new RetryingProvider(), accounts);

        StepVerifier.create(coordinator.execute(request("alpha", true))
                .takeUntil(CanonicalEvent.Completed.class::isInstance))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(CanonicalEvent.Usage.class::isInstance)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();

        verify(accounts).reportSuccess(second, "model");
        verify(accounts).release(second);
    }

    @Test
    void doesNotRetryAfterMeaningfulOutputWasVisible() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any()))
            .thenReturn(Mono.just(leased));
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any())).thenReturn(Mono.just(false));
        when(accounts.reportModelCooldown(
            leased, "model", "empty", java.time.Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());
        var coordinator = coordinator(new RetryingProvider(true, true), accounts);

        StepVerifier.create(coordinator.execute(request("alpha", true)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(event -> event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("empty_model_response"))
            .verifyComplete();

        verify(accounts).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).release(leased);
    }

    @Test
    void retriesANonStreamingFailureEvenAfterTheAttemptStartedAResponse() {
        var accounts = mock(AccountSelectionService.class);
        var first = leased("alpha");
        var second = leased("alpha");
        when(accounts.acquire(eq("alpha"), eq("model"), any()))
            .thenReturn(Mono.just(first), Mono.just(second));
        when(accounts.release(any())).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(any(), any())).thenReturn(Mono.just(false));
        when(accounts.reportModelCooldown(
            first, "model", "empty", java.time.Duration.ofMinutes(5)))
            .thenReturn(Mono.empty());
        when(accounts.reportSuccess(second, "model")).thenReturn(Mono.empty());
        var coordinator = coordinator(new RetryingProvider(true), accounts);

        StepVerifier.create(coordinator.execute(request("alpha", false)))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .expectNextMatches(CanonicalEvent.Usage.class::isInstance)
            .expectNextMatches(CanonicalEvent.Completed.class::isInstance)
            .verifyComplete();

        verify(accounts, times(2)).acquire(eq("alpha"), eq("model"), any());
        verify(accounts).release(first);
        verify(accounts).release(second);
    }

    @Test
    void persistsCredentialPatchBeforeReleasingACancelledStream() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        var patch = JsonNodeFactory.instance.objectNode().put("session", "rotated");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(leased, patch)).thenReturn(Mono.just(true));
        var coordinator = coordinator(new CancellingProvider(patch), accounts);

        StepVerifier.create(coordinator.execute(request("alpha", true), leased))
            .expectNextMatches(CanonicalEvent.ResponseStarted.class::isInstance)
            .expectNextMatches(CanonicalEvent.OutputTextDelta.class::isInstance)
            .thenCancel()
            .verify();

        verify(accounts).mergeCredentialPatch(leased, patch);
        verify(accounts).release(leased);
    }

    private CanonicalRequest request(String providerId) {
        return request(providerId, false);
    }

    private CanonicalRequest request(String providerId, boolean stream) {
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user").put("content", "hello");
        return new CanonicalRequest(
            "request-id",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            providerId,
            "model",
            stream,
            List.of(message),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            JsonNodeFactory.instance.objectNode().put("stream", stream));
    }

    private InferenceCoordinator coordinator(
        InferenceProvider provider,
        AccountSelectionService accounts
    ) {
        return coordinator(provider, accounts, mock(ProviderTransportModeService.class));
    }

    private InferenceCoordinator coordinator(
        InferenceProvider provider,
        AccountSelectionService accounts,
        ProviderTransportModeService transportModes
    ) {
        var telemetry = mock(InferenceTelemetryService.class);
        var started = mock(InferenceTelemetryService.Started.class);
        when(telemetry.start(
            any(InferenceTelemetryService.InferenceTrace.class), anyInt(), anyLong()))
            .thenReturn(started);
        var catalog = mock(ModelCatalogCache.class);
        when(catalog.find(anyString(), anyString()))
            .thenReturn(Mono.just(java.util.Optional.empty()));
        when(transportModes.plan(any())).thenReturn(new ProviderTransportModeService.TransportPlan(
            ProviderTransportMode.RUNTIME, ProviderTransportMode.RUNTIME, null));
        return new InferenceCoordinator(
            ProviderRegistry.allEnabled(List.of(provider)),
            accounts,
            new ProviderFailureDisposition(
                accounts, mock(com.any2api.lifecycle.AccountRecoveryService.class)),
            telemetry,
            new ModelRuntimeGuard(
                new Any2ApiProperties(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new UsageNormalizer(), callableAvailability(), catalog,
            new ModelRequestLimitGuard(), transportModes,
            new com.any2api.protocol.SmartContextWindowManager(new tools.jackson.databind.ObjectMapper()),
            null);
    }

    private ModelAvailabilityGuard callableAvailability() {
        var availability = mock(ModelAvailabilityGuard.class);
        when(availability.requireCallable(anyString(), anyString())).thenReturn(Mono.empty());
        return availability;
    }

    private LeasedProviderAccount leased(String providerId) {
        var accountId = UUID.randomUUID();
        return new LeasedProviderAccount(
            accountId,
            providerId,
            "external",
            null,
            1,
            null,
            JsonNodeFactory.instance.objectNode(),
            Map.of(),
            new AccountLease(
                providerId,
                accountId,
                UUID.randomUUID().toString(),
                1,
                Instant.now().plusSeconds(300)));
    }

    private static final class TestProvider implements InferenceProvider {
        private final boolean reject;
        private final CanonicalEvent event;

        private TestProvider(boolean reject) {
            this(reject, null);
        }

        private TestProvider(boolean reject, CanonicalEvent event) {
            this.reject = reject;
            this.event = event;
        }

        @Override
        public ProviderManifest manifest() {
            return new ProviderManifest(
                "alpha",
                "Alpha",
                "test",
                "1",
                List.of(),
                Map.of(
                    ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                    ProviderCapability.STREAMING, SupportLevel.NATIVE),
                true);
        }

        @Override
        public void validate(CanonicalRequest request) {
            if (reject) {
                throw new IllegalArgumentException("request rejected for test");
            }
        }

        @Override
        public Flux<CanonicalEvent> generate(
            CanonicalRequest request,
            ProviderExecutionContext context,
            LeasedProviderAccount account
        ) {
            return event == null ? Flux.empty() : Flux.just(event);
        }

        @Override
        public ProviderFailure classify(Throwable error) {
            return new ProviderFailure("test", error.getMessage(), false, Map.of());
        }
    }

    private static final class RetryingProvider implements InferenceProvider {
        private final AtomicInteger attempts = new AtomicInteger();
        private final boolean exposeResponseBeforeFailure;
        private final boolean exposeOutputBeforeFailure;
        private final String failureType;
        private final int failedAttempts;

        private RetryingProvider() {
            this(false, false);
        }

        private RetryingProvider(boolean exposeResponseBeforeFailure) {
            this(exposeResponseBeforeFailure, false);
        }

        private RetryingProvider(
            boolean exposeResponseBeforeFailure,
            boolean exposeOutputBeforeFailure
        ) {
            this.exposeResponseBeforeFailure = exposeResponseBeforeFailure;
            this.exposeOutputBeforeFailure = exposeOutputBeforeFailure;
            this.failureType = "empty_model_response";
            this.failedAttempts = 1;
        }

        private RetryingProvider(String failureType, int failedAttempts, boolean exposeOutputBeforeFailure) {
            this.exposeResponseBeforeFailure = true;
            this.exposeOutputBeforeFailure = exposeOutputBeforeFailure;
            this.failureType = failureType;
            this.failedAttempts = failedAttempts;
        }

        @Override
        public ProviderManifest manifest() {
            return new ProviderManifest(
                "alpha", "Alpha", "test", "1", List.of(),
                Map.of(
                    ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                    ProviderCapability.STREAMING, SupportLevel.NATIVE),
                true);
        }

        @Override
        public ProviderRetryPolicy retryPolicy() {
            return ProviderRetryPolicy.standardWith(
                List.of("credential_rejected", "tool_call_generation_failed").contains(failureType) ? 3 : 2,
                failureType);
        }

        @Override
        public Flux<CanonicalEvent> generate(
            CanonicalRequest request,
            ProviderExecutionContext context,
            LeasedProviderAccount account
        ) {
            if (attempts.getAndIncrement() < failedAttempts) {
                if (exposeResponseBeforeFailure) {
                    var events = new java.util.ArrayList<CanonicalEvent>();
                    events.add(new CanonicalEvent.ResponseStarted(
                        1, request.requestId(), 0, "response-id"));
                    if (exposeOutputBeforeFailure) {
                        events.add(new CanonicalEvent.OutputTextDelta(
                            1, request.requestId(), 1, "partial"));
                    }
                    events.add(new CanonicalEvent.Failed(
                        1, request.requestId(), exposeOutputBeforeFailure ? 2 : 1,
                        failureType, "empty", Map.of()));
                    return Flux.fromIterable(events);
                }
                return Flux.just(new CanonicalEvent.Failed(
                    1, request.requestId(), 0,
                    failureType, "empty", Map.of()));
            }
            return Flux.just(
                new CanonicalEvent.ResponseStarted(
                    1, request.requestId(), 0, "response-id"),
                new CanonicalEvent.OutputTextDelta(
                    1, request.requestId(), 1, "ok"),
                new CanonicalEvent.Completed(
                    1, request.requestId(), 2, "stop"));
        }

        @Override
        public ProviderFailure classify(Throwable error) {
            return new ProviderFailure("test", error.getMessage(), false, Map.of());
        }
    }

    private static final class CancellingProvider implements InferenceProvider {
        private final tools.jackson.databind.JsonNode patch;

        private CancellingProvider(tools.jackson.databind.JsonNode patch) {
            this.patch = patch;
        }

        @Override
        public ProviderManifest manifest() {
            return new ProviderManifest(
                "alpha", "Alpha", "test", "1", List.of(),
                Map.of(
                    ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                    ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                    ProviderCapability.STREAMING, SupportLevel.NATIVE),
                true);
        }

        @Override
        public Flux<CanonicalEvent> generate(
            CanonicalRequest request,
            ProviderExecutionContext context,
            LeasedProviderAccount account
        ) {
            context.acceptCredentialPatch(patch);
            return Flux.concat(
                Flux.just(
                    new CanonicalEvent.ResponseStarted(
                        1, request.requestId(), 0, "response-id"),
                    new CanonicalEvent.OutputTextDelta(
                        1, request.requestId(), 1, "partial")),
                Flux.never());
        }

        @Override
        public ProviderFailure classify(Throwable error) {
            return new ProviderFailure("test", error.getMessage(), false, Map.of());
        }
    }
}
