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
    private final ProviderRegistry providers;
    private final int batchSize;
    private final Duration freshness;

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
        this.providers = providers;
        this.batchSize = properties.getModelRuntime().getScheduledProbeBatchSize();
        this.freshness = properties.getModelRuntime().getProbeFreshness();
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
        return Mono.fromCallable(() -> {
            var staleBefore = Instant.now().minus(freshness.dividedBy(2));
            List<Candidate> result = new ArrayList<>();
            for (var provider : providers.plugins()) {
                if (!provider.scheduledModelProbeEnabled()) {
                    continue;
                }
                var providerId = provider.manifest().id();
                var preferredModel = provider.scheduledProbeModel().orElse("");
                var candidateModel = jdbc.sql("""
                        SELECT model.upstream_id
                        FROM models model
                        JOIN providers provider ON provider.id = model.provider_id
                        LEFT JOIN model_probe_results probe
                          ON probe.provider_id = model.provider_id
                         AND probe.model_id = model.upstream_id
                        WHERE model.provider_id = :providerId
                          AND model.enabled = TRUE
                          AND provider.enabled = TRUE
                          AND provider.installed = TRUE
                          AND (probe.probed_at IS NULL
                            OR probe.probed_at < :staleBefore)
                          AND NOT EXISTS (
                            SELECT 1 FROM model_probe_results fresh_probe
                            WHERE fresh_probe.provider_id = model.provider_id
                              AND fresh_probe.status = 'READY'
                              AND fresh_probe.probed_at >= :staleBefore
                          )
                        ORDER BY
                          CASE WHEN model.upstream_id = :preferredModel THEN 0 ELSE 1 END,
                          CASE
                            WHEN model.upstream_id LIKE '%-image%'
                              OR model.upstream_id LIKE '%-video%'
                              OR model.upstream_id LIKE '%-i2v%'
                              OR model.upstream_id LIKE '%-t2v%'
                              OR model.upstream_id LIKE '%-t2i%'
                              OR model.upstream_id LIKE '%-edit%'
                              OR model.upstream_id LIKE 'flux-%'
                              OR model.upstream_id LIKE 'wan-%'
                              OR model.upstream_id LIKE 'wan2%'
                              OR model.upstream_id LIKE 'veo-%'
                              OR model.upstream_id LIKE 'kling-%'
                              OR model.upstream_id LIKE 'sora%'
                            THEN 1 ELSE 0
                          END ASC,
                          model.updated_at DESC,
                          model.id DESC
                        LIMIT 1
                        """)
                    .param("providerId", providerId)
                    .param("preferredModel", preferredModel)
                    .param("staleBefore", PostgresResultValues.timestamp(staleBefore))
                    .query((row, ignored) -> row.getString("upstream_id"))
                    .optional();
                if (candidateModel.isPresent()) {
                    result.add(new Candidate(providerId, candidateModel.get()));
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
