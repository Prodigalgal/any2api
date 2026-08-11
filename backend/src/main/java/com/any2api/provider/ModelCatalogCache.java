package com.any2api.provider;

import com.any2api.cache.LayeredJsonCache;
import com.any2api.config.Any2ApiProperties;
import com.any2api.persistence.PostgresResultValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelCatalogCache {
    private static final String MODEL_QUERY = """
        SELECT m.upstream_id, m.display_name, m.provider_id,
               p.display_name AS provider_name,
               m.capabilities::text AS discovered_capabilities,
               m.max_context_tokens_override,
               m.max_input_tokens_override,
               m.max_output_tokens_override,
               m.catalog_source, m.metadata::text, m.random_roles, m.fetched_at,
               account_runtime.eligible_accounts,
               account_runtime.available_accounts,
               account_runtime.quota_limited_accounts,
               usage_runtime.request_count,
               usage_runtime.attempt_count,
               usage_runtime.success_count,
               usage_runtime.success_rate,
               usage_runtime.p50_ms,
               usage_runtime.p95_ms,
               usage_runtime.last_attempt_at,
               usage_runtime.last_success_at,
               probe.status AS probe_status,
               probe.error_class AS probe_error,
               probe.probed_at,
               CASE
                   WHEN account_runtime.eligible_accounts = 0
                     OR account_runtime.available_accounts = 0 THEN 'UNAVAILABLE'
                   WHEN probe.status = 'FAILED' AND probe.probed_at >= :probeFreshAfter
                     AND (usage_runtime.last_success_at IS NULL
                       OR probe.probed_at > usage_runtime.last_success_at) THEN 'UNAVAILABLE'
                   WHEN usage_runtime.request_count >= 3
                     AND usage_runtime.success_rate >= :readySuccessRate
                     AND usage_runtime.p95_ms <= :readyP95Ms THEN 'READY'
                   WHEN usage_runtime.request_count < 3
                     AND probe.status = 'READY' AND probe.probed_at >= :probeFreshAfter THEN 'READY'
                   WHEN usage_runtime.attempt_count >= 3
                     AND usage_runtime.success_count = 0 THEN 'UNAVAILABLE'
                   ELSE 'DEGRADED'
               END AS runtime_status,
               account_runtime.available_accounts > 0
                 AND NOT COALESCE(
                   probe.status = 'FAILED' AND probe.probed_at >= :probeFreshAfter
                     AND (usage_runtime.last_success_at IS NULL
                       OR probe.probed_at > usage_runtime.last_success_at),
                   FALSE
                 )
                 AND (
                   usage_runtime.success_count > 0
                   OR (probe.status = 'READY' AND probe.probed_at >= :probeFreshAfter)
                 ) AS available
        FROM models m
        JOIN providers p ON p.id = m.provider_id
        LEFT JOIN LATERAL (
            SELECT
                COUNT(*) AS eligible_accounts,
                COUNT(*) FILTER (WHERE NOT EXISTS (
                    SELECT 1 FROM account_model_cooldowns cooldown
                    WHERE cooldown.account_id = account.id
                      AND cooldown.provider_id = m.provider_id
                      AND cooldown.model_id = m.upstream_id
                      AND cooldown.cooldown_until > CURRENT_TIMESTAMP
                )) AS available_accounts,
                COUNT(*) FILTER (WHERE EXISTS (
                    SELECT 1 FROM account_model_cooldowns cooldown
                    WHERE cooldown.account_id = account.id
                      AND cooldown.provider_id = m.provider_id
                      AND cooldown.model_id = m.upstream_id
                      AND cooldown.cooldown_until > CURRENT_TIMESTAMP
                      AND cooldown.reason ~* '(rate|quota|limit|credit|balance)'
                )) AS quota_limited_accounts
            FROM accounts account
            WHERE account.provider_id = m.provider_id
              AND account.enabled = TRUE
              AND account.status IN ('ACTIVE', 'DEGRADED')
              AND (account.cooldown_until IS NULL OR account.cooldown_until <= CURRENT_TIMESTAMP)
              AND (account.expires_at IS NULL OR account.expires_at > CURRENT_TIMESTAMP)
        ) account_runtime ON TRUE
        LEFT JOIN LATERAL (
            SELECT COUNT(*) AS request_count,
                   COALESCE(SUM(usage.attempt_count), 0) AS attempt_count,
                   COUNT(*) FILTER (WHERE usage.success) AS success_count,
                   COALESCE(AVG(CASE WHEN usage.success THEN 1.0 ELSE 0.0 END), 0.0)
                       AS success_rate,
                   COALESCE(ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP
                       (ORDER BY usage.duration_ms)), 0)::BIGINT AS p50_ms,
                   COALESCE(ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP
                       (ORDER BY usage.duration_ms)), 0)::BIGINT AS p95_ms,
                   MAX(usage.created_at) AS last_attempt_at,
                   MAX(usage.created_at) FILTER (WHERE usage.success) AS last_success_at
            FROM (
                SELECT logical.request_id,
                       (ARRAY_AGG(logical.success ORDER BY logical.attempt DESC))[1] AS success,
                       COUNT(*) AS attempt_count,
                       SUM(logical.duration_ms) AS duration_ms,
                       MAX(logical.created_at) AS created_at
                FROM usage_events logical
                WHERE logical.provider_id = m.provider_id
                  AND logical.model_id = m.upstream_id
                  AND logical.created_at >= :windowStart
                GROUP BY logical.request_id
            ) usage
        ) usage_runtime ON TRUE
        LEFT JOIN model_probe_results probe
          ON probe.provider_id = m.provider_id AND probe.model_id = m.upstream_id
        WHERE m.enabled = TRUE AND p.installed = TRUE AND p.enabled = TRUE
        ORDER BY m.provider_id, m.upstream_id
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LayeredJsonCache cache;
    private final java.time.Duration healthWindow;
    private final java.time.Duration probeFreshness;
    private final double readySuccessRate;
    private final long readyP95Ms;

    public ModelCatalogCache(
        JdbcClient jdbc,
        ObjectMapper mapper,
        ReactiveStringRedisTemplate redis,
        Any2ApiProperties properties
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        var policy = properties.getCache().getModelCatalog();
        this.cache = new LayeredJsonCache(
            redis, "any2api:cache:model-catalog:v3", policy.getLocalTtl(),
            policy.getRedisTtl(), policy.getMaximumEntries());
        this.healthWindow = properties.getModelRuntime().getHealthWindow();
        this.probeFreshness = properties.getModelRuntime().getProbeFreshness();
        this.readySuccessRate = properties.getModelRuntime().getReadySuccessRateThreshold() / 100.0;
        this.readyP95Ms = properties.getModelRuntime().getReadyP95Threshold().toMillis();
    }

    public Mono<List<Entry>> list() {
        return cache.get("enabled", () -> Mono.fromCallable(() -> Optional.of(
                mapper.writeValueAsString(load())))
            .subscribeOn(Schedulers.boundedElastic()))
            .map(value -> value.map(this::decode).orElseGet(List::of));
    }

    public Mono<Optional<Entry>> find(String providerId, String modelId) {
        return list().map(entries -> entries.stream()
            .filter(entry -> entry.providerId().equals(providerId) && entry.id().equals(modelId))
            .findFirst());
    }

    public Mono<Void> invalidate() {
        return cache.evict("enabled");
    }

    public void invalidateAfterCommit() {
        Runnable action = () -> invalidate().subscribe();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
    }

    private List<Entry> load() {
        var now = Instant.now();
        return jdbc.sql(MODEL_QUERY)
            .param("windowStart", PostgresResultValues.timestamp(now.minus(healthWindow)))
            .param("probeFreshAfter", PostgresResultValues.timestamp(now.minus(probeFreshness)))
            .param("readySuccessRate", readySuccessRate)
            .param("readyP95Ms", readyP95Ms)
            .query(this::row).list();
    }

    private List<Entry> decode(String value) {
        var type = mapper.getTypeFactory().constructCollectionType(List.class, Entry.class);
        return mapper.readValue(value, type);
    }

    private Entry row(ResultSet result, int rowNumber) throws SQLException {
        var fetchedAt = result.getObject("fetched_at", java.time.OffsetDateTime.class);
        var discoveredCapabilities = readJson(result.getString("discovered_capabilities"));
        var maxContextTokensOverride = nullableLong(result, "max_context_tokens_override");
        var maxInputTokensOverride = nullableLong(result, "max_input_tokens_override");
        var maxOutputTokensOverride = nullableLong(result, "max_output_tokens_override");
        return new Entry(
            result.getString("upstream_id"),
            result.getString("display_name"),
            result.getString("provider_id"),
            result.getString("provider_name"),
            effectiveCapabilities(
                discoveredCapabilities,
                maxContextTokensOverride,
                maxInputTokensOverride,
                maxOutputTokensOverride),
            discoveredCapabilities,
            maxContextTokensOverride,
            maxInputTokensOverride,
            maxOutputTokensOverride,
            result.getString("catalog_source"),
            readJson(result.getString("metadata")),
            List.of((String[]) result.getArray("random_roles").getArray()),
            fetchedAt == null ? Instant.now().getEpochSecond() : fetchedAt.toEpochSecond(),
            result.getBoolean("available"),
            result.getString("runtime_status"),
            result.getLong("eligible_accounts"),
            result.getLong("available_accounts"),
            result.getLong("quota_limited_accounts"),
            result.getLong("request_count"),
            result.getLong("attempt_count"),
            result.getDouble("success_rate"),
            result.getLong("p50_ms"),
            result.getLong("p95_ms"),
            PostgresResultValues.instant(result, "last_attempt_at"),
            PostgresResultValues.instant(result, "last_success_at"),
            result.getString("probe_status"),
            result.getString("probe_error"),
            PostgresResultValues.instant(result, "probed_at"));
    }

    private JsonNode readJson(String value) {
        return value == null || value.isBlank()
            ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : mapper.readTree(value);
    }

    private Long nullableLong(ResultSet result, String field) throws SQLException {
        var value = result.getObject(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    private JsonNode effectiveCapabilities(
        JsonNode discovered,
        Long maxContextTokens,
        Long maxInputTokens,
        Long maxOutputTokens
    ) {
        var effective = discovered != null && discovered.isObject()
            ? (tools.jackson.databind.node.ObjectNode) discovered.deepCopy()
            : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        putOverride(effective, "max_context_tokens", maxContextTokens);
        putOverride(effective, "max_input_tokens", maxInputTokens);
        putOverride(effective, "max_output_tokens", maxOutputTokens);
        return effective;
    }

    private void putOverride(
        tools.jackson.databind.node.ObjectNode capabilities,
        String field,
        Long value
    ) {
        if (value != null) capabilities.put(field, value);
    }

    public record Entry(
        String id,
        String displayName,
        String providerId,
        String providerName,
        JsonNode capabilities,
        JsonNode discoveredCapabilities,
        Long maxContextTokensOverride,
        Long maxInputTokensOverride,
        Long maxOutputTokensOverride,
        String catalogSource,
        JsonNode metadata,
        List<String> randomRoles,
        long created,
        boolean available,
        String runtimeStatus,
        long eligibleAccountCount,
        long availableAccountCount,
        long quotaLimitedAccountCount,
        long rollingRequestCount,
        long rollingAttemptCount,
        double rollingSuccessRate,
        long p50Ms,
        long p95Ms,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        String probeStatus,
        String probeError,
        Instant probedAt
    ) {}
}
