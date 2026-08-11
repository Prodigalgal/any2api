package com.any2api.lifecycle;

import com.any2api.account.AccountRepository;
import com.any2api.account.AccountSelectionService;
import com.any2api.account.AccountStatus;
import com.any2api.account.AccountView;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.observability.OperationContext;
import com.any2api.observability.OperationEventService;
import com.any2api.observability.RequestCorrelation;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderFailureDisposition;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ModelCatalogCache;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public final class AccountProbeService {
    private final AccountRepository repository;
    private final AccountSelectionService accounts;
    private final ProviderRegistry providers;
    private final ProviderFailureDisposition failures;
    private final InferenceReadinessProbe readiness;
    private final OperationEventService observability;
    private final TransactionTemplate transactions;
    private final ExecutorService databaseExecutor;
    private final ModelCatalogCache modelCatalog;

    public AccountProbeService(
        AccountRepository repository,
        AccountSelectionService accounts,
        ProviderRegistry providers,
        ProviderFailureDisposition failures,
        InferenceReadinessProbe readiness,
        OperationEventService observability,
        PlatformTransactionManager transactionManager,
        ExecutorService databaseExecutor,
        ModelCatalogCache modelCatalog
    ) {
        this.repository = repository;
        this.accounts = accounts;
        this.providers = providers;
        this.failures = failures;
        this.readiness = readiness;
        this.observability = observability;
        this.transactions = new TransactionTemplate(transactionManager);
        this.databaseExecutor = databaseExecutor;
        this.modelCatalog = modelCatalog;
    }

    public Mono<Result> probe(UUID accountId) {
        return probe(accountId, null);
    }

    public Mono<Result> probe(UUID accountId, String requestedModel) {
        return Mono.fromCallable(() -> Objects.requireNonNull(transactions.execute(ignored -> {
                var account = require(accountId);
                var provider = providers.require(account.getProviderId());
                if (!provider.manifest().configured()) {
                    throw new IllegalArgumentException("provider is not configured");
                }
                return account.getProviderId();
            })))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMap(providerId -> resolveModel(providerId, requestedModel)
                .flatMap(model -> execute(accountId, providerId, model.orElse(null))));
    }

    private Mono<java.util.Optional<String>> resolveModel(
        String providerId,
        String requestedModel
    ) {
        var model = requestedModel == null ? "" : requestedModel.trim();
        if (model.isBlank()) return Mono.just(java.util.Optional.empty());
        return modelCatalog.find(providerId, model).map(entry -> {
            if (entry.isEmpty()) {
                throw new IllegalArgumentException(
                    "model is not enabled for provider " + providerId + ": " + model);
            }
            return java.util.Optional.of(model);
        });
    }

    private Mono<Result> execute(UUID accountId, String providerId, String model) {
        var context = new OperationContext(
            UUID.randomUUID().toString(), "ACCOUNT", accountId.toString(), 1);
        var observed = observability.start("LIFECYCLE", providerId, "probe", context);
        observability.linkAccount(observed, accountId);
        return Mono.usingWhen(
                accounts.acquire(accountId),
                account -> Mono.defer(() -> model == null
                        ? readiness.probe(
                            account, providers.require(providerId).accountProbeTimeout())
                        : readiness.probe(
                            account, providers.require(providerId).accountProbeTimeout(), model))
                    .flatMap(result -> accounts.mergeCredentialPatch(
                            account, result.credentialPatch())
                        .onErrorReturn(false)
                        .then(applyDisposition(account, result))
                        .then(persist(accountId, result))),
                accounts::release,
                (account, ignored) -> accounts.release(account),
                accounts::release)
            .doOnSuccess(result -> {
                if (result.ready()) {
                    observability.succeed(observed, "inference_probe_ready");
                } else {
                    observability.fail(observed, result.errorClass(), "inference_probe",
                        "the selected account did not return a usable model response");
                }
            })
            .doOnError(error -> observability.fail(observed, error))
            .doOnCancel(() -> observability.fail(
                observed,
                "request_cancelled",
                "client_cancelled",
                "account probe request was cancelled"))
            .contextWrite(RequestCorrelation.context(context.correlationId()));
    }

    private Mono<Void> applyDisposition(
        LeasedProviderAccount account,
        InferenceReadinessProbe.Result result
    ) {
        if (result.ready()) return Mono.empty();
        return failures.report(account, result.model(), new ProviderFailure(
            result.errorClass(), result.errorClass(), false, Map.of("source", "manual_probe")));
    }

    private Mono<Result> persist(UUID accountId, InferenceReadinessProbe.Result result) {
        return Mono.fromCallable(() -> Objects.requireNonNull(transactions.execute(ignored -> {
                var account = require(accountId);
                var completedAt = Instant.now();
                account.mergeMetadata(Map.of(
                    "inference_probe_at", completedAt.toString(),
                    "inference_probe_model", result.model(),
                    "inference_probe_status", result.ready() ? "READY" : "FAILED",
                    "inference_probe_error", result.errorClass(),
                    "inference_readiness_pending", false));
                if (result.ready()) {
                    account.updateState(AccountStatus.ACTIVE, true);
                    account.markSuccess(completedAt);
                }
                account = repository.save(account);
                return new Result(
                    result.ready(), result.model(), result.errorClass(), result.output(),
                    result.durationMs(), completedAt,
                    AccountView.from(account));
            })))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    private com.any2api.account.AccountEntity require(UUID accountId) {
        return repository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
    }

    public record Result(
        boolean ready,
        String model,
        String errorClass,
        String output,
        long durationMs,
        Instant completedAt,
        AccountView account
    ) {
    }
}
