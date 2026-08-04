package com.any2api.media;

import com.any2api.account.AccountSelectionService;
import java.time.Duration;
import com.any2api.provider.ProviderFailureDisposition;
import com.any2api.provider.ProviderRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import com.any2api.observability.InferenceTelemetryService;
import com.any2api.observability.RequestCorrelation;
import java.util.UUID;

@Service
public final class MediaCoordinator {
    private final ProviderMediaRegistry handlers;
    private final ProviderRegistry providers;
    private final AccountSelectionService accounts;
    private final ProviderFailureDisposition failures;
    private final InferenceTelemetryService telemetry;

    public MediaCoordinator(
        ProviderMediaRegistry handlers,
        ProviderRegistry providers,
        AccountSelectionService accounts,
        ProviderFailureDisposition failures,
        InferenceTelemetryService telemetry
    ) {
        this.handlers = handlers;
        this.providers = providers;
        this.accounts = accounts;
        this.failures = failures;
        this.telemetry = telemetry;
    }

    public Mono<ExecutionResult> execute(MediaRequest request) {
        return execute(request, null);
    }

    public Mono<ExecutionResult> execute(MediaRequest request, UUID apiKeyId) {
        providers.require(request.providerId());
        var handler = handlers.require(request);
        var observed = telemetry.start(new InferenceTelemetryService.InferenceTrace(
            request.requestId(), request.providerId(), request.model(),
            request.operation().name(), apiKeyId, "INFERENCE", request.rawRequest()), 1);
        return Mono.usingWhen(
            accounts.acquire(request.providerId(), request.model(), account ->
                handler.supportsAccount(request, account)),
            account -> {
                observed.account(account.accountId());
                return handler.generate(request, account)
                .map(result -> new ExecutionResult(account.accountId(), result))
                .flatMap(result -> accounts.mergeCredentialPatch(
                        account, result.result().credentialPatch())
                    .onErrorReturn(false)
                    .then(accounts.reportSuccess(account, request.model()))
                    .thenReturn(result))
                .onErrorResume(error -> {
                    var failure = handler.classify(error);
                    return failures.report(account, request.model(), failure)
                        .then(Mono.error(new MediaProviderException(failure, error)));
                });
            },
            accounts::release,
            (account, ignored) -> accounts.release(account),
            accounts::release)
            .doOnNext(result -> observed.output(result.result().items()))
            .doOnError(observed::recordError)
            .doFinally(observed::finish)
            .contextWrite(RequestCorrelation.context(request.requestId()));
    }

    public record ExecutionResult(java.util.UUID accountId, MediaResult result) {}

    public static final class MediaProviderException extends RuntimeException {
        private final com.any2api.provider.ProviderFailure failure;

        MediaProviderException(
            com.any2api.provider.ProviderFailure failure,
            Throwable cause
        ) {
            super(failure.message(), cause);
            this.failure = failure;
        }

        public com.any2api.provider.ProviderFailure failure() { return failure; }
    }
}
