package com.any2api.provider;

import com.any2api.config.Any2ApiProperties;
import com.any2api.persistence.PostgresResultValues;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public final class ModelProbeScheduler {
    private static final Logger log = LoggerFactory.getLogger(ModelProbeScheduler.class);
    private final JdbcClient jdbc;
    private final ExecutorService databaseExecutor;
    private final ModelProbeService probes;
    private final int batchSize;
    private final Duration freshness;
    private final Set<String> scheduledProviderIds;
    private final Map<String, String> scheduledTargets;

    public ModelProbeScheduler(
        JdbcClient jdbc,
        ExecutorService databaseExecutor,
        ModelProbeService probes,
        ProviderRegistry providers,
        Any2ApiProperties properties
    ) {
        this.jdbc = jdbc;
        this.databaseExecutor = databaseExecutor;
        this.probes = probes;
        this.batchSize = properties.getModelRuntime().getScheduledProbeBatchSize();
        this.freshness = properties.getModelRuntime().getProbeFreshness();
        var targets = new LinkedHashMap<String, String>();
        for (var provider : providers.plugins()) {
            if (provider.scheduledModelProbeEnabled()) {
                provider.scheduledProbeModel().ifPresent(model ->
                    targets.put(provider.manifest().id(), model));
            }
        }
        this.scheduledTargets = Map.copyOf(targets);
        this.scheduledProviderIds = Set.copyOf(this.scheduledTargets.keySet());
    }

    @Scheduled(
        initialDelayString = "${any2api.model-runtime.probe-initial-delay:2m}",
        fixedDelayString = "${any2api.model-runtime.probe-interval:15m}"
    )
    public void probeStaleModels() {
        candidates().flatMapMany(Flux::fromIterable)
            .concatMap(candidate -> probes.probe(candidate.providerId(), candidate.modelId())
                .doOnNext(result -> log.info(
                    "model_probe_completed provider={} model={} status={} error={} duration_ms={}",
                    result.providerId(), result.modelId(), result.status(), result.errorClass(),
                    result.durationMs()))
                .onErrorResume(error -> {
                    log.warn("model_probe_failed provider={} model={} error_type={}",
                        candidate.providerId(), candidate.modelId(), error.getClass().getSimpleName());
                    return Mono.empty();
                }))
            .then()
            .block(Duration.ofMinutes(10));
    }

    private Mono<List<Candidate>> candidates() {
        if (scheduledTargets.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
            var staleBefore = Instant.now().minus(freshness);
            List<Candidate> result = new ArrayList<>();
            for (var entry : scheduledTargets.entrySet()) {
                var providerId = entry.getKey();
                var modelId = entry.getValue();
                var stale = jdbc.sql("""
                        SELECT 1
                        FROM models model
                        JOIN providers provider ON provider.id = model.provider_id
                        LEFT JOIN model_probe_results probe
                          ON probe.provider_id = model.provider_id
                         AND probe.model_id = model.upstream_id
                        WHERE model.provider_id = :providerId
                          AND model.upstream_id = :modelId
                          AND model.enabled = TRUE
                          AND provider.enabled = TRUE
                          AND provider.installed = TRUE
                          AND (probe.probed_at IS NULL
                            OR probe.probed_at < :staleBefore)
                        LIMIT 1
                        """)
                    .param("providerId", providerId)
                    .param("modelId", modelId)
                    .param("staleBefore", PostgresResultValues.timestamp(staleBefore))
                    .query((row, ignored) -> true)
                    .optional()
                    .orElse(false);
                if (stale) {
                    result.add(new Candidate(providerId, modelId));
                    if (result.size() >= batchSize) {
                        break;
                    }
                }
            }
            return result;
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    private record Candidate(String providerId, String modelId) {}
}
