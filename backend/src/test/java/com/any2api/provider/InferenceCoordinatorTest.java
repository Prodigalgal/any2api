package com.any2api.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class InferenceCoordinatorTest {

    @Test
    void releasesPreleasedRandomAccountWhenProviderValidationFails() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        var provider = new TestProvider(true);
        var coordinator = coordinator(provider, accounts);

        StepVerifier.create(coordinator.execute(request("alpha"), leased))
            .expectErrorMatches(error -> error instanceof IllegalArgumentException
                && error.getMessage().contains("rejected"))
            .verify();

        verify(accounts).release(leased);
    }

    @Test
    void doesNotReportSuccessWhenAProviderEmitsCanonicalFailure() {
        var accounts = mock(AccountSelectionService.class);
        var leased = leased("alpha");
        when(accounts.release(leased)).thenReturn(Mono.just(true));
        when(accounts.mergeCredentialPatch(eq(leased), any(tools.jackson.databind.JsonNode.class)))
            .thenReturn(Mono.just(true));
        var provider = new TestProvider(false, new CanonicalEvent.Failed(
            1, "request-id", 1, "tool_call_generation_failed", "missing tool", Map.of()));
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
        var telemetry = mock(InferenceTelemetryService.class);
        var started = mock(InferenceTelemetryService.Started.class);
        when(telemetry.start(
            any(InferenceTelemetryService.InferenceTrace.class), anyInt(), anyLong()))
            .thenReturn(started);
        return new InferenceCoordinator(
            new ProviderRegistry(List.of(provider)),
            accounts,
            new ProviderFailureDisposition(accounts),
            telemetry,
            new ModelRuntimeGuard(
                new Any2ApiProperties(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new UsageNormalizer());
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
            return new ProviderRetryPolicy(2, java.util.Set.of("empty_model_response"));
        }

        @Override
        public Flux<CanonicalEvent> generate(
            CanonicalRequest request,
            ProviderExecutionContext context,
            LeasedProviderAccount account
        ) {
            if (attempts.getAndIncrement() == 0) {
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
                        "empty_model_response", "empty", Map.of()));
                    return Flux.fromIterable(events);
                }
                return Flux.just(new CanonicalEvent.Failed(
                    1, request.requestId(), 0,
                    "empty_model_response", "empty", Map.of()));
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
}
