package com.any2api.provider;

import com.any2api.account.AccountSelectionService;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public InferenceCoordinator(
        ProviderRegistry providers,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures
    ) {
        this.providers = providers;
        this.accounts = accounts;
        this.failures = failures;
    }

    public Flux<CanonicalEvent> execute(CanonicalRequest request) {
        var provider = providers.require(request.providerId());
        ProviderRequestValidation.requireSupportedRequest(request, provider.manifest());
        provider.validate(request);
        return executeWithRetries(request, provider,
            accountLease(request, provider), false, 1);
    }

    public Flux<CanonicalEvent> execute(
        CanonicalRequest request,
        com.any2api.account.LeasedProviderAccount account
    ) {
        if (!request.providerId().equals(account.providerId())) {
            return accounts.release(account).thenMany(Flux.error(
                new IllegalArgumentException(
                    "random route account provider does not match the request")));
        }
        var provider = providers.require(request.providerId());
        return executeWithRetries(
            request, provider, reactor.core.publisher.Mono.just(account), true, 1);
    }

    private reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> accountLease(
        CanonicalRequest request,
        InferenceProvider provider
    ) {
        return accounts.acquire(request.providerId(), request.model(),
            account -> provider.supportsAccount(request, account));
    }

    private Flux<CanonicalEvent> executeWithRetries(
        CanonicalRequest request,
        InferenceProvider provider,
        reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> lease,
        boolean validateInsideLease,
        int attempt
    ) {
        return executeWithLease(request, provider, lease, validateInsideLease)
            .switchOnFirst((signal, events) -> {
                if (signal.hasValue()
                    && signal.get() instanceof CanonicalEvent.Failed failure
                    && provider.retryPolicy().shouldRetry(failure.errorType(), attempt)) {
                    return events.thenMany(executeWithRetries(
                        request, provider, accountLease(request, provider), false, attempt + 1));
                }
                return events;
            });
    }

    private Flux<CanonicalEvent> executeWithLease(
        CanonicalRequest request,
        InferenceProvider provider,
        reactor.core.publisher.Mono<com.any2api.account.LeasedProviderAccount> lease,
        boolean validateInsideLease
    ) {
        return Flux.usingWhen(
            lease,
            account -> {
                if (validateInsideLease) {
                    ProviderRequestValidation.requireSupportedRequest(
                        request, provider.manifest());
                    provider.validate(request);
                }
                var failed = new AtomicBoolean();
                var lastSequence = new AtomicLong();
                var context = new ProviderExecutionContext(
                    request.requestId(),
                    account.accountId(),
                    Long.toString(account.credentialVersion()),
                    account.lease().ownerToken(),
                    account.lease().fencingToken(),
                    Instant.now().plus(REQUEST_DEADLINE));
                return withLeaseRenewal(
                    Flux.defer(() -> provider.generate(request, context, account)), account)
                    .concatMap(event -> {
                        lastSequence.accumulateAndGet(event.sequenceNumber(), Math::max);
                        if (event instanceof CanonicalEvent.Failed failure) {
                            failed.set(true);
                            var providerFailure = new ProviderFailure(
                                failure.errorType(), failure.message(), false, failure.detail());
                            return accounts.mergeCredentialPatch(
                                    account, context.credentialPatch())
                                .onErrorReturn(false)
                                .then(failures.report(account, request.model(), providerFailure))
                                .thenReturn(event);
                        }
                        return reactor.core.publisher.Mono.just(event);
                    })
                    .onErrorResume(error -> {
                        failed.set(true);
                        var failure = provider.classify(error);
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
                    })
                    .concatWith(Flux.defer(() -> {
                        return failed.get() ? Flux.empty()
                            : accounts.mergeCredentialPatch(account, context.credentialPatch())
                                .onErrorReturn(false)
                                .then(accounts.reportSuccess(account, request.model()))
                                .thenMany(Flux.empty());
                    }));
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
