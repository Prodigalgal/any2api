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
import tools.jackson.databind.JsonNode;

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
    private final ModelCatalogCache catalog;
    private final ModelRequestLimitGuard requestLimits;
    private final ProviderTransportModeService transportModes;
    private final com.any2api.protocol.SmartContextWindowManager contextManager;
    private final com.any2api.cache.PromptExactCacheManager promptCache;

    public InferenceCoordinator(
        ProviderRegistry providers,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures,
        InferenceTelemetryService telemetry,
        ModelRuntimeGuard runtime,
        UsageNormalizer usage,
        ModelAvailabilityGuard availability,
        ModelCatalogCache catalog,
        ModelRequestLimitGuard requestLimits,
        ProviderTransportModeService transportModes,
        com.any2api.protocol.SmartContextWindowManager contextManager,
        com.any2api.cache.PromptExactCacheManager promptCache
    ) {
        this.providers = providers;
        this.accounts = accounts;
        this.failures = failures;
        this.telemetry = telemetry;
        this.runtime = runtime;
        this.usage = usage;
        this.availability = availability;
        this.catalog = catalog;
        this.requestLimits = requestLimits;
        this.transportModes = transportModes;
        this.contextManager = contextManager;
        this.promptCache = promptCache;
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request) {
        return execute(request, (UUID) null);
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request, UUID apiKeyId) {
        return execute(request, apiKeyId, null, "INFERENCE");
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        UUID apiKeyId,
        ProviderTransportMode requestedTransportMode
    ) {
        return execute(request, apiKeyId, requestedTransportMode, "INFERENCE");
    }

    public Flux<CanonicalEvent> executeProbe(CanonicalRequest request) {
        return execute(request, null, null, "PROBE");
    }

    private Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        UUID apiKeyId,
        ProviderTransportMode requestedTransportMode,
        String requestKind
    ) {
        var provider = providers.require(request.providerId());
        var transportPlan = requestedTransportMode == null
            ? transportModes.plan(provider)
            : transportModes.plan(provider, requestedTransportMode);
        return catalog.find(request.providerId(), request.model()).flatMapMany(model -> {
            var modelCapabilities = model.map(ModelCatalogCache.Entry::capabilities).orElse(null);
            var safeRequest = contextManager.guard(request, modelCapabilities);
            validateRequest(safeRequest, provider, modelCapabilities);
            model.ifPresent(entry -> requestLimits.requireWithinLimits(
                safeRequest, entry.capabilities()));
            var execution = runtime.execute(safeRequest, admission -> executeWithRetries(
                safeRequest, provider, accountLease(safeRequest, provider), false, 1, apiKeyId,
                requestKind, admission.queueMs(), transportPlan.primary(),
                transportPlan.fallback(), modelCapabilities));
            var liveEvents = "PROBE".equals(requestKind) ? execution
                : availability.requireCallable(safeRequest.providerId(), safeRequest.model())
                    .thenMany(execution);
            if (promptCache == null || !promptCache.isEligibleForCache(safeRequest) || "PROBE".equals(requestKind)) {
                return liveEvents;
            }
            return promptCache.get(safeRequest)
                .flatMapMany(Flux::fromIterable)
                .switchIfEmpty(Flux.defer(() -> liveEvents.collectList().flatMapMany(events ->
                    promptCache.put(safeRequest, events).thenReturn(events).flatMapMany(Flux::fromIterable))));
        });
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account
    ) {
        return execute(request, account, null, null, "INFERENCE");
    }

    public Flux<CanonicalEvent> executeProbe(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account
    ) {
        return execute(request, account, null, null, "PROBE");
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account,
        UUID apiKeyId
    ) {
        return execute(request, account, apiKeyId, null, "INFERENCE");
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account,
        UUID apiKeyId,
        ProviderTransportMode requestedTransportMode
    ) {
        return execute(request, account, apiKeyId, requestedTransportMode, "INFERENCE");
    }

    private Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account,
        UUID apiKeyId,
        ProviderTransportMode requestedTransportMode,
        String requestKind
    ) {
        if (!request.providerId().equals(account.providerId())) {
            return accounts.release(account).thenMany(Flux.error(
                new IllegalArgumentException(
                    "random route account provider does not match the request")));
        }
        var provider = providers.require(request.providerId());
        var transportPlan = requestedTransportMode == null
            ? transportModes.plan(provider)
            : transportModes.plan(provider, requestedTransportMode);
        return catalog.find(request.providerId(), request.model()).flatMapMany(model -> {
            var modelCapabilities = model.map(ModelCatalogCache.Entry::capabilities).orElse(null);
            var safeRequest = contextManager.guard(request, modelCapabilities);
            model.ifPresent(entry -> requestLimits.requireWithinLimits(
                safeRequest, entry.capabilities()));
            var execution = runtime.execute(safeRequest, admission ->
                    executeWithRetries(safeRequest, provider, reactor.core.publisher.Mono.just(account),
                        true, 1, apiKeyId, requestKind, admission.queueMs(),
                        transportPlan.primary(), transportPlan.fallback(), modelCapabilities))
                .onErrorResume(ModelRuntimeGuard.ModelRuntimeRejectedException.class,
                    error -> accounts.release(account).thenMany(Flux.error(error)));
            var liveEvents = "PROBE".equals(requestKind) ? execution
                : availability.requireCallable(safeRequest.providerId(), safeRequest.model())
                    .thenMany(execution)
                    .onErrorResume(ModelAvailabilityGuard.ModelUnavailableException.class,
                        error -> accounts.release(account).thenMany(Flux.error(error)));
            if (promptCache == null || !promptCache.isEligibleForCache(safeRequest) || "PROBE".equals(requestKind)) {
                return liveEvents;
            }
            return promptCache.get(safeRequest)
                .flatMapMany(Flux::fromIterable)
                .switchIfEmpty(Flux.defer(() -> liveEvents.collectList().flatMapMany(events ->
                    promptCache.put(safeRequest, events).thenReturn(events).flatMapMany(Flux::fromIterable))));
        });
    }

    private reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> accountLease(
        CanonicalRequest request,
        InferenceProvider provider
    ) {
        return accountLease(request, provider, java.util.Set.of());
    }

    private reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> accountLease(
        CanonicalRequest request,
        InferenceProvider provider,
        java.util.Set<UUID> excludedAccountIds
    ) {
        return accounts.acquire(request.providerId(), request.model(),
            account -> (excludedAccountIds == null || !excludedAccountIds.contains(account.accountId()))
                && provider.supportsAccount(request, account));
    }

    private void validateRequest(
        CanonicalRequest request,
        InferenceProvider provider,
        JsonNode modelCapabilities
    ) {
        try {
            ProviderRequestValidation.requireSupportedRequest(
                request, provider.manifest(), provider.protocolContract(), modelCapabilities);
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
        long queueMs,
        ProviderTransportMode transportMode,
        ProviderTransportMode fallbackTransportMode,
        JsonNode modelCapabilities
    ) {
        return executeWithRetries(
            request, provider, lease, validateInsideLease, attempt, apiKeyId,
            requestKind, queueMs, transportMode, fallbackTransportMode, modelCapabilities,
            java.util.Set.of());
    }

    private Flux<CanonicalEvent> executeWithRetries(
        CanonicalRequest request,
        InferenceProvider provider,
        reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> lease,
        boolean validateInsideLease,
        int attempt,
        UUID apiKeyId,
        String requestKind,
        long queueMs,
        ProviderTransportMode transportMode,
        ProviderTransportMode fallbackTransportMode,
        JsonNode modelCapabilities,
        java.util.Set<UUID> attemptedAccountIds
    ) {
        var currentAccountId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var wrappedLease = lease.doOnNext(account -> currentAccountId.set(account.accountId()));
        var attemptEvents = Flux.defer(() -> {
            var observed = telemetry.start(new InferenceTelemetryService.InferenceTrace(
                request.requestId(), request.providerId(), request.model(),
                request.protocol().name(), apiKeyId, requestKind, request.rawRequest(),
                transportMode.externalName()),
                attempt, queueMs);
            return usage.normalize(request,
                    executeWithLease(
                        request, provider, wrappedLease, validateInsideLease, observed, transportMode,
                        modelCapabilities))
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
                var nextExcluded = new java.util.HashSet<UUID>(attemptedAccountIds);
                if (currentAccountId.get() != null) {
                    nextExcluded.add(currentAccountId.get());
                }
                if (failure.isPresent() && fallbackTransportMode != null
                    && shouldFallbackToRuntime(failure.get().errorType())) {
                    return executeWithRetries(
                        request, provider, accountLease(request, provider, nextExcluded), false, 1,
                        apiKeyId, requestKind, 0, fallbackTransportMode, null,
                        modelCapabilities, nextExcluded);
                }
                if (failure.isPresent()
                    && provider.retryPolicy().shouldRetry(failure.get().errorType(), attempt)) {
                    return executeWithRetries(
                        request, provider, accountLease(request, provider, nextExcluded), false,
                        attempt + 1, apiKeyId, requestKind, 0,
                        transportMode, fallbackTransportMode, modelCapabilities, nextExcluded);
                }
                return Flux.fromIterable(events);
            });
        }
        return attemptEvents.switchOnFirst((signal, events) -> {
                var nextExcluded = new java.util.HashSet<UUID>(attemptedAccountIds);
                if (currentAccountId.get() != null) {
                    nextExcluded.add(currentAccountId.get());
                }
                if (signal.hasValue()
                    && signal.get() instanceof CanonicalEvent.Failed failure
                    && fallbackTransportMode != null
                    && shouldFallbackToRuntime(failure.errorType())) {
                    return events.thenMany(executeWithRetries(
                        request, provider, accountLease(request, provider, nextExcluded), false, 1,
                        apiKeyId, requestKind, 0, fallbackTransportMode, null,
                        modelCapabilities, nextExcluded));
                }
                if (signal.hasValue()
                    && signal.get() instanceof CanonicalEvent.Failed failure
                    && provider.retryPolicy().shouldRetry(failure.errorType(), attempt)) {
                    return events.thenMany(executeWithRetries(
                        request, provider, accountLease(request, provider, nextExcluded), false,
                        attempt + 1, apiKeyId, requestKind, 0,
                        transportMode, fallbackTransportMode, modelCapabilities, nextExcluded));
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
        InferenceTelemetryService.Started observed,
        ProviderTransportMode transportMode,
        JsonNode modelCapabilities
    ) {
        return Flux.usingWhen(
            lease.map(account -> new ExecutionLease(account, new ProviderExecutionContext(
                request.requestId(),
                account.accountId(),
                Long.toString(account.credentialVersion()),
                account.lease().ownerToken(),
                account.lease().fencingToken(),
                Instant.now().plus(REQUEST_DEADLINE), transportMode))),
            execution -> {
                var account = execution.account();
                var context = execution.context();
                observed.account(account.accountId());
                observed.accountAcquired();
                if (validateInsideLease) {
                    ProviderRequestValidation.requireSupportedRequest(
                        request, provider.manifest(), provider.protocolContract(), modelCapabilities);
                    provider.validate(request);
                }
                var lastSequence = new AtomicLong();
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
                                    account, context.takeCredentialPatch())
                                .onErrorReturn(false)
                                .then(failures.report(account, request.model(), providerFailure))
                                .thenReturn(event);
                        }
                        if (event instanceof CanonicalEvent.Completed) {
                            return accounts.mergeCredentialPatch(
                                    account, context.takeCredentialPatch())
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
                                account, context.takeCredentialPatch())
                            .onErrorReturn(false)
                            .then(failures.report(account, request.model(), failure))
                            .thenMany(Flux.just(event));
                    });
            },
            this::settleAndRelease,
            (execution, ignored) -> settleAndRelease(execution),
            this::settleAndRelease);
    }

    private reactor.core.publisher.Mono<Void> settleAndRelease(ExecutionLease execution) {
        var patch = execution.context().takeCredentialPatch();
        var persistence = patch.isObject() && !patch.isEmpty()
            ? accounts.mergeCredentialPatch(execution.account(), patch).onErrorReturn(false)
            : reactor.core.publisher.Mono.just(false);
        return persistence
            .then(accounts.release(execution.account()))
            .then();
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

    private record ExecutionLease(
        com.any2api.account.LeasedProviderAccount account,
        ProviderExecutionContext context
    ) {}

    private boolean shouldFallbackToRuntime(String failureType) {
        return ProviderTransportFallbackPolicy.allowsRuntimeFallback(failureType);
    }
}
