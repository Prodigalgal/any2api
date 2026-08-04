package com.any2api.auth;

import com.any2api.persistence.PostgresResultValues;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyUsageService {
    private final ApiKeyService keys;
    private final JdbcClient jdbc;

    public ApiKeyUsageService(ApiKeyService keys, JdbcClient jdbc) {
        this.keys = keys;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        var key = keys.get(id);
        var now = Instant.now();
        return new Detail(
            key,
            window(id, Instant.EPOCH),
            window(id, now.minus(24, ChronoUnit.HOURS)),
            window(id, now.minus(7, ChronoUnit.DAYS)),
            window(id, now.minus(30, ChronoUnit.DAYS)),
            breakdown(id, now.minus(30, ChronoUnit.DAYS)));
    }

    private UsageWindow window(UUID id, Instant since) {
        return jdbc.sql("""
            WITH attempts AS (
                SELECT * FROM usage_events
                WHERE api_key_id = :id AND created_at >= :since
            ), logical_requests AS (
                SELECT request_id,
                       (ARRAY_AGG(success ORDER BY attempt DESC))[1] AS success,
                       SUM(duration_ms) AS duration_ms,
                       MAX(created_at) AS last_used_at
                FROM attempts GROUP BY request_id
            ), logical_totals AS (
                SELECT COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE success) AS success_count,
                       COUNT(*) FILTER (WHERE NOT success) AS failure_count,
                       COALESCE(ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP
                           (ORDER BY duration_ms)), 0)::BIGINT AS p50_duration_ms,
                       COALESCE(ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP
                           (ORDER BY duration_ms)), 0)::BIGINT AS p95_duration_ms,
                       MAX(last_used_at) AS last_used_at
                FROM logical_requests
            ), attempt_totals AS (
                SELECT COUNT(*) AS attempt_count,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens
                FROM attempts
            )
            SELECT * FROM logical_totals CROSS JOIN attempt_totals
            """)
            .param("id", id)
            .param("since", PostgresResultValues.timestamp(since))
            .query((row, ignored) -> new UsageWindow(
                row.getLong("request_count"), row.getLong("attempt_count"),
                row.getLong("success_count"), row.getLong("failure_count"),
                row.getLong("input_tokens"), row.getLong("output_tokens"),
                row.getLong("cache_read_tokens"), row.getLong("p50_duration_ms"),
                row.getLong("p95_duration_ms"),
                PostgresResultValues.instant(row, "last_used_at"))).single();
    }

    private List<ModelUsage> breakdown(UUID id, Instant since) {
        return jdbc.sql("""
            SELECT provider_id, model_id, COUNT(DISTINCT request_id) AS request_count,
                   COUNT(DISTINCT request_id) FILTER (WHERE success) AS success_count,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP
                       (ORDER BY duration_ms)), 0)::BIGINT AS p95_duration_ms,
                   MAX(created_at) AS last_used_at
            FROM usage_events
            WHERE api_key_id = :id AND created_at >= :since
            GROUP BY provider_id, model_id
            ORDER BY request_count DESC, provider_id, model_id
            LIMIT 100
            """)
            .param("id", id).param("since", PostgresResultValues.timestamp(since))
            .query((row, ignored) -> new ModelUsage(
                row.getString("provider_id"), row.getString("model_id"),
                row.getLong("request_count"), row.getLong("success_count"),
                row.getLong("input_tokens"), row.getLong("output_tokens"),
                row.getLong("p95_duration_ms"),
                PostgresResultValues.instant(row, "last_used_at"))).list();
    }

    public record Detail(
        ApiKeyService.View key,
        UsageWindow lifetime,
        UsageWindow last24Hours,
        UsageWindow last7Days,
        UsageWindow last30Days,
        List<ModelUsage> modelUsage
    ) {}

    public record UsageWindow(
        long requestCount,
        long attemptCount,
        long successCount,
        long failureCount,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long p50DurationMs,
        long p95DurationMs,
        Instant lastUsedAt
    ) {}

    public record ModelUsage(
        String providerId,
        String modelId,
        long requestCount,
        long successCount,
        long inputTokens,
        long outputTokens,
        long p95DurationMs,
        Instant lastUsedAt
    ) {}
}
