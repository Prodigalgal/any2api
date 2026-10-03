package com.any2api.provider;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public final class ModelProbeService {
    private static final Logger log = LoggerFactory.getLogger(ModelProbeService.class);
    private final ProviderRegistry providers;
    private final InferenceCoordinator coordinator;
    private final JdbcClient jdbc;
    private final ExecutorService databaseExecutor;
    private final ModelCatalogCache catalog;

    public ModelProbeService(
        ProviderRegistry providers,
        InferenceCoordinator coordinator,
        JdbcClient jdbc,
        ExecutorService databaseExecutor,
        ModelCatalogCache catalog
    ) {
        this.providers = providers;
        this.coordinator = coordinator;
        this.jdbc = jdbc;
        this.databaseExecutor = databaseExecutor;
        this.catalog = catalog;
    }

    public Mono<Result> probe(String providerId, String modelId) {
        var normalizedProvider = required(providerId, "provider_id");
        var normalizedModel = required(modelId, "model_id");
        var provider = providers.require(normalizedProvider);
        var request = request(normalizedProvider, normalizedModel);
        var startedAt = System.nanoTime();
        return requireCataloged(normalizedProvider, normalizedModel).then(Mono.defer(() ->
            coordinator.executeProbe(request)
                .collectList()
                .timeout(provider.modelProbeTimeout())
                .flatMap(events -> {
                    var result = result(normalizedProvider, normalizedModel, events, startedAt);
                    if (java.util.Set.of("credential_rejected", "anti_bot_rejected")
                        .contains(result.errorClass())) {
                        log.info("model_probe_deferred provider={} model={} reason={}",
                            normalizedProvider, normalizedModel, result.errorClass());
                        return Mono.empty();
                    }
                    return Mono.just(result);
                })
                .onErrorResume(error -> {
                    var root = error;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    if (root instanceof com.any2api.account.AccountUnavailableException
                        || root instanceof com.any2api.coordination.AccountCapacityException) {
                        log.info("model_probe_deferred provider={} model={} reason={}",
                            normalizedProvider, normalizedModel, root.getClass().getSimpleName());
                        return Mono.empty();
                    }
                    if (ProviderFailureSignals.isTimeout(error)) {
                        return Mono.just(new Result(
                            normalizedProvider, normalizedModel, "FAILED", "upstream_timeout",
                            null, elapsed(startedAt), Instant.now()));
                    }
                    // A local preparation/transport exception is not evidence that
                    // the upstream model failed. Preserve the last measured state.
                    log.warn("model_probe_infrastructure_failed provider={} model={} error_type={} cause_type={}",
                        normalizedProvider, normalizedModel, error.getClass().getSimpleName(),
                        root.getClass().getSimpleName());
                    return Mono.error(error);
                })
                .flatMap(this::persist)));
    }

    /**
     * Records a successful account-level inference probe as model-level readiness evidence.
     * The caller may invoke this inside its own transaction so account and model state commit together.
     */
    public void recordReadyEvidence(
        String providerId,
        String modelId,
        UUID accountId,
        long durationMs,
        Instant probedAt
    ) {
        var result = new Result(
            required(providerId, "provider_id"),
            required(modelId, "model_id"),
            "READY",
            "",
            accountId,
            Math.max(0, durationMs),
            probedAt == null ? Instant.now() : probedAt);
        writeResult(result);
        catalog.invalidateAfterCommit();
    }

    private Mono<Void> requireCataloged(String providerId, String modelId) {
        return Mono.fromCallable(() -> jdbc.sql("""
                SELECT COUNT(*)
                FROM models model
                JOIN providers provider ON provider.id = model.provider_id
                WHERE model.provider_id = :providerId
                  AND model.upstream_id = :modelId
                  AND model.enabled = TRUE
                  AND provider.enabled = TRUE
                  AND provider.installed = TRUE
                """)
            .param("providerId", providerId)
            .param("modelId", modelId)
            .query(Long.class)
            .single())
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMap(count -> count > 0
                ? Mono.empty()
                : Mono.error(new IllegalArgumentException(
                    "model is not cataloged or enabled: " + providerId + "/" + modelId)));
    }

    private CanonicalRequest request(String providerId, String modelId) {
        var requestId = "model-probe-" + UUID.randomUUID();
        var message = JsonNodeFactory.instance.objectNode()
            .put("role", "user")
            .put("content", "Hello! Please reply with a short confirmation message.");
        var raw = JsonNodeFactory.instance.objectNode()
            .put("model", modelId)
            .put("stream", false);
        return new CanonicalRequest(
            requestId, CanonicalRequest.Protocol.CHAT_COMPLETIONS, providerId, modelId,
            false, List.of(message), Map.of(), Map.of(), List.of(), Map.of(), raw);
    }

    private Result result(
        String providerId,
        String modelId,
        List<CanonicalEvent> events,
        long startedAt
    ) {
        var failure = events.stream()
            .filter(CanonicalEvent.Failed.class::isInstance)
            .map(CanonicalEvent.Failed.class::cast).findFirst();
        var output = events.stream()
            .filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta).reduce("", String::concat);
        var completed = events.stream()
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
        if (failure.isPresent()) {
            return new Result(providerId, modelId, "FAILED", failure.get().errorType(),
                null, elapsed(startedAt), Instant.now());
        }
        if (!completed || output.isBlank()) {
            return new Result(providerId, modelId, "FAILED", "empty_model_response",
                null, elapsed(startedAt), Instant.now());
        }
        return new Result(providerId, modelId, "READY", "", null,
            elapsed(startedAt), Instant.now());
    }

    private Mono<Result> persist(Result result) {
        return Mono.fromCallable(() -> {
                writeResult(result);
                return result;
            })
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMap(resultValue -> catalog.invalidate().thenReturn(resultValue));
    }

    private void writeResult(Result result) {
        jdbc.sql("""
                INSERT INTO model_probe_results(
                    provider_id, model_id, account_id, status, error_class,
                    duration_ms, probed_at)
                VALUES (:providerId, :modelId, :accountId, :status, :errorClass,
                        :durationMs, :probedAt)
                ON CONFLICT (provider_id, model_id) DO UPDATE SET
                    account_id = EXCLUDED.account_id,
                    status = EXCLUDED.status,
                    error_class = EXCLUDED.error_class,
                    duration_ms = EXCLUDED.duration_ms,
                    probed_at = EXCLUDED.probed_at
                """)
            .param("providerId", result.providerId())
            .param("modelId", result.modelId())
            .param("accountId", result.accountId())
            .param("status", result.status())
            .param("errorClass", result.errorClass().isBlank() ? null : result.errorClass())
            .param("durationMs", result.durationMs())
            .param("probedAt", PostgresResultValues.timestamp(result.probedAt()))
            .update();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long elapsed(long startedAt) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedAt));
    }

    public record Result(
        String providerId,
        String modelId,
        String status,
        String errorClass,
        UUID accountId,
        long durationMs,
        Instant probedAt
    ) {}
}
