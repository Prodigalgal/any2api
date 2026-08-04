package com.any2api.provider;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public final class ModelProbeService {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

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
        providers.require(normalizedProvider);
        var request = request(normalizedProvider, normalizedModel);
        var startedAt = System.nanoTime();
        return requireCataloged(normalizedProvider, normalizedModel).then(Mono.defer(() ->
            coordinator.executeProbe(request)
                .collectList()
                .timeout(TIMEOUT)
                .map(events -> result(normalizedProvider, normalizedModel, events, startedAt))
                .onErrorResume(error -> Mono.just(new Result(
                    normalizedProvider, normalizedModel, "FAILED",
                    error.getClass().getSimpleName(), null, elapsed(startedAt), Instant.now())))
                .flatMap(this::persist)));
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
            .put("content", "Reply briefly with ANY2API_MODEL_OK");
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
                return result;
            })
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
            .flatMap(resultValue -> catalog.invalidate().thenReturn(resultValue));
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
