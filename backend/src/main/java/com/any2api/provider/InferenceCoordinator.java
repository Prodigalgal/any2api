package com.any2api.provider;

import com.any2api.account.AccountSelectionService;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalEventStream;
import com.any2api.protocol.CanonicalProtocolException;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalResponseGuard;
import com.any2api.protocol.UsageNormalizer;
import com.any2api.observability.InferenceTelemetryService;
import com.any2api.observability.RequestCorrelation;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class InferenceCoordinator {

    private static final Duration REQUEST_DEADLINE = Duration.ofHours(2);
    private static final Duration LEASE_RENEW_INTERVAL = Duration.ofMinutes(2);

    private final ProviderRegistry providers;
    private final AccountSelectionService accounts;
    private final ProviderFailureDisposition failures;
    private final InferenceTelemetryService telemetry;
    private final ModelRuntimeGuard runtime;
    private final UsageNormalizer usage;
    private final ModelAvailabilityGuard availability;

    public InferenceCoordinator(
        ProviderRegistry providers,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures,
        InferenceTelemetryService telemetry,
        ModelRuntimeGuard runtime,
        UsageNormalizer usage,
        ModelAvailabilityGuard availability
    ) {
        this.providers = providers;
        this.accounts = accounts;
        this.failures = failures;
        this.telemetry = telemetry;
        this.runtime = runtime;
        this.usage = usage;
        this.availability = availability;
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request) {
        return execute(request, (UUID) null);
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request, UUID apiKeyId) {
        return execute(request, apiKeyId, "INFERENCE");
    }

    public Flux<CanonicalEvent> executeProbe(CanonicalRequest request) {
        return execute(request, null, "PROBE");
    }

    private Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        UUID apiKeyId,
        String requestKind
    ) {
        var provider = providers.require(request.providerId());
        validateRequest(request, provider);
        var execution = runtime.execute(request, admission -> executeWithRetries(
            request, provider, accountLease(request, provider), false, 1, apiKeyId,
            requestKind, admission.queueMs()));
        return "PROBE".equals(requestKind) ? execution
            : availability.requireCallable(request.providerId(), request.model())
                .thenMany(execution);
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account
    ) {
        return execute(request, account, null, "INFERENCE");
    }

    public Flux<CanonicalEvent> executeProbe(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account
    ) {
        return execute(request, account, null, "PROBE");
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account,
        UUID apiKeyId
    ) {
        return execute(request, account, apiKeyId, "INFERENCE");
    }

    private Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account,
        UUID apiKeyId,
        String requestKind
    ) {
        if (!request.providerId().equals(account.providerId())) {
            return accounts.release(account).thenMany(Flux.error(
                new IllegalArgumentException(
                    "random route account provider does not match the request")));
        }
        var provider = providers.require(request.providerId());
        var execution = runtime.execute(request, admission ->
                executeWithRetries(request, provider, reactor.core.publisher.Mono.just(account),
                    true, 1, apiKeyId, requestKind, admission.queueMs()))
            .onErrorResume(ModelRuntimeGuard.ModelRuntimeRejectedException.class,
                error -> accounts.release(account).thenMany(Flux.error(error)));
        if ("PROBE".equals(requestKind)) return execution;
        return availability.requireCallable(request.providerId(), request.model())
            .thenMany(execution)
            .onErrorResume(ModelAvailabilityGuard.ModelUnavailableException.class,
                error -> accounts.release(account).thenMany(Flux.error(error)));
    }

    private reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> accountLease(
        CanonicalRequest request,
        InferenceProvider provider
    ) {
        return accounts.acquire(request.providerId(), request.model(),
            account -> provider.supportsAccount(request, account));
    }

    private void validateRequest(CanonicalRequest request, InferenceProvider provider) {
        try {
            ProviderRequestValidation.requireSupportedRequest(
                request, provider.manifest(), provider.protocolContract());
            provider.validate(request);
        } catch (com.any2api.protocol.OpenAiRequestException error) {
            throw error.withAcceptedParameters(ProviderRequestValidation.acceptedParameters(
                request.protocol(), provider.protocolContract()));
        }
    }

    private Flux<CanonicalEvent> executeWithRetries(
        CanonicalRequest request,
        InferenceProvider provider,
        reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> lease,
        boolean validateInsideLease,
        int attempt,
        UUID apiKeyId,
        String requestKind,
        long queueMs
    ) {
        var attemptEvents = Flux.defer(() -> {
            var observed = telemetry.start(new InferenceTelemetryService.InferenceTrace(
                request.requestId(), request.providerId(), request.model(),
                request.protocol().name(), apiKeyId, requestKind, request.rawRequest()),
                attempt, queueMs);
            return usage.normalize(request,
                    executeWithLease(request, provider, lease, validateInsideLease, observed))
                .doOnNext(event -> recordTelemetry(observed, event))
                .doOnError(observed::recordError)
                .doFinally(observed::finish);
        }).contextWrite(RequestCorrelation.context(request.requestId()));
        if (!request.stream()) {
            return attemptEvents.collectList().flatMapMany(events -> {
                var failure = events.stream()
                    .filter(CanonicalEvent.Failed.class::isInstance)
                    .map(CanonicalEvent.Failed.class::cast)
                    .findFirst();
                if (failure.isPresent()
                    && provider.retryPolicy().shouldRetry(failure.get().errorType(), attempt)) {
                    return executeWithRetries(
                        request, provider, accountLease(request, provider), false,
                        attempt + 1, apiKeyId, requestKind, 0);
                }
                return Flux.fromIterable(events);
            });
        }
        return attemptEvents.switchOnFirst((signal, events) -> {
                if (signal.hasValue()
                    && signal.get() instanceof CanonicalEvent.Failed failure
                    && provider.retryPolicy().shouldRetry(failure.errorType(), attempt)) {
                    return events.thenMany(executeWithRetries(
                        request, provider, accountLease(request, provider), false,
                        attempt + 1, apiKeyId, requestKind, 0));
                }
                return events;
            });
    }

    private void recordTelemetry(
        InferenceTelemetryService.Started observed,
        CanonicalEvent event
    ) {
        observed.record(event);
        if (!(event instanceof CanonicalEvent.Usage)) observed.firstByte();
        if (event instanceof CanonicalEvent.Completed || event instanceof CanonicalEvent.Failed) {
            observed.terminal();
        }
        if (event instanceof CanonicalEvent.Usage usage) {
            observed.usage(
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.source());
        } else if (event instanceof CanonicalEvent.Failed failed) {
            observed.failure(failed.errorType());
        }
    }

    private Flux<CanonicalEvent> executeWithLease(
        CanonicalRequest request,
        InferenceProvider provider,
        reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> lease,
        boolean validateInsideLease,
        InferenceTelemetryService.Started observed
    ) {
        return Flux.usingWhen(
            lease,
            account -> {
                observed.account(account.accountId());
                observed.accountAcquired();
                if (validateInsideLease) {
                    ProviderRequestValidation.requireSupportedRequest(
                        request, provider.manifest(), provider.protocolContract());
                    provider.validate(request);
                }
                var lastSequence = new AtomicLong();
                var context = new ProviderExecutionContext(
                    request.requestId(),
                    account.accountId(),
                    Long.toString(account.credentialVersion()),
                    account.lease().ownerToken(),
                    account.lease().fencingToken(),
                    Instant.now().plus(REQUEST_DEADLINE));
                return withLeaseRenewal(
                    CanonicalEventStream.enforce(
                        request,
                        Flux.defer(() -> provider.generate(request, context, account))),
                    account)
                    .transform(events -> CanonicalResponseGuard.holdUntilMeaningfulOutput(
                        request, events))
                    .concatMap(event -> {
                        lastSequence.accumulateAndGet(event.sequenceNumber(), Math::max);
                        if (event instanceof CanonicalEvent.Failed failure) {
                            var providerFailure = new ProviderFailure(
                                failure.errorType(), failure.message(), false, failure.detail());
                            return accounts.mergeCredentialPatch(
                                    account, context.credentialPatch())
                                .onErrorReturn(false)
                                .then(failures.report(account, request.model(), providerFailure))
                                .thenReturn(event);
                        }
                        if (event instanceof CanonicalEvent.Completed) {
                            return accounts.mergeCredentialPatch(
                                    account, context.credentialPatch())
                                .onErrorReturn(false)
                                .then(accounts.reportSuccess(account, request.model()))
                                .thenReturn(event);
                        }
                        return reactor.core.publisher.Mono.just(event);
                    })
                    .onErrorResume(error -> {
                        var failure = error instanceof CanonicalProtocolException protocolError
                            ? new ProviderFailure(
                                "provider_protocol_violation",
                                "provider emitted an invalid canonical event stream",
                                false,
                                Map.of("violation", protocolError.violation()))
                            : provider.classify(error);
                        var event = new CanonicalEvent.Failed(
                            1,
                            request.requestId(),
                            lastSequence.incrementAndGet(),
                            failure.type(),
                            failure.message(),
                            failure.detail() == null ? Map.of() : failure.detail());
                        return accounts.mergeCredentialPatch(
                                account, context.credentialPatch())
                            .onErrorReturn(false)
                            .then(failures.report(account, request.model(), failure))
                            .thenMany(Flux.just(event));
                    });
            },
            accounts::release,
            (account, ignored) -> accounts.release(account),
            accounts::release);
    }

    private Flux<CanonicalEvent> withLeaseRenewal(
        Flux<CanonicalEvent> upstream,
        com.any2api.account.LeasedProviderAccount account
    ) {
        return upstream.publish(shared -> {
            var completed = shared.then();
            var renewalGuard = Flux.interval(LEASE_RENEW_INTERVAL)
                .takeUntilOther(completed)
                .concatMap(ignored -> accounts.renew(account))
                .flatMap(renewed -> renewed
                    ? reactor.core.publisher.Mono.<CanonicalEvent>empty()
                    : reactor.core.publisher.Mono.error(
                        new IllegalStateException("account lease renewal failed")));
            return Flux.merge(shared, renewalGuard);
        });
    }
}
