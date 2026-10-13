package com.any2api.lifecycle;

import com.any2api.provider.AdvancedModelProbePolicy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public final class InitialInferenceCheckService {
    private static final Logger LOGGER = LoggerFactory.getLogger(InitialInferenceCheckService.class);
    private final InitialInferenceCheckRepository repository;
    private final ExecutorService databaseExecutor;

    public InitialInferenceCheckService(InitialInferenceCheckRepository repository, ExecutorService databaseExecutor) {
        this.repository = repository;
        this.databaseExecutor = databaseExecutor;
    }

    Mono<Optional<Observation>> existing(UUID accountId) {
        return Mono.fromCallable(() -> repository.find(accountId).map(check -> observation(check, false)))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    Mono<Observation> verify(UUID accountId, Supplier<Plan> planFactory) {
        return existing(accountId).flatMap(existing -> {
            if (existing.isPresent()) return Mono.just(existing.get());
            var plan = planFactory.get();
            return Mono.fromCallable(() -> repository.claim(accountId, plan.model()))
                .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                .flatMap(claim -> {
                    if (!claim.claimed()) return Mono.just(observation(claim.check(), false));
                    // The committed unique claim consumes the budget even if this process is interrupted.
                    return Mono.defer(plan.work())
                        .timeout(java.time.Duration.ofMinutes(3))
                        .switchIfEmpty(Mono.fromSupplier(() -> InferenceReadinessProbe.Result.failed(
                            plan.model(), "InitialVerificationEmptyResult")))
                        .onErrorResume(error -> {
                            LOGGER.warn("initial inference check failed error_type={}", error.getClass().getSimpleName());
                            return Mono.just(InferenceReadinessProbe.Result.failed(
                                plan.model(), "InitialVerification_" + error.getClass().getSimpleName()));
                        })
                        .flatMap(result -> Mono.fromCallable(() -> repository.complete(claim.check(), result))
                            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                            .map(check -> new Observation(result, true, check.source(), check.completedAt())))
                        .doOnCancel(() -> Mono.fromRunnable(() -> repository.interrupted(claim.check()))
                            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                            .subscribe(ignored -> {}, error -> LOGGER.warn(
                                "initial inference cancellation record failed error_type={}",
                                error.getClass().getSimpleName())));
                });
        });
    }

    private static Observation observation(InitialInferenceCheckRepository.Check check, boolean performed) {
        return new Observation(check.result(), performed, check.source(), check.completedAt());
    }

    record Plan(String model, Supplier<Mono<InferenceReadinessProbe.Result>> work) {
        Plan {
            if (model == null || model.isBlank() || AdvancedModelProbePolicy.isAdvancedModel(model)) {
                throw new IllegalArgumentException("initial verification requires a basic text model");
            }
            java.util.Objects.requireNonNull(work, "initial verification work");
        }
    }

    record Observation(InferenceReadinessProbe.Result result, boolean performed, String source,
                       java.time.Instant completedAt) {}
}
