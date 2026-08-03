package com.any2api.api.admin;

import com.any2api.persistence.PostgresResultValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/v1/observability")
public final class AdminObservabilityController {
    private final JdbcClient jdbc;
    private final ExecutorService databaseExecutor;

    public AdminObservabilityController(JdbcClient jdbc, ExecutorService databaseExecutor) {
        this.jdbc = jdbc;
        this.databaseExecutor = databaseExecutor;
    }

    @GetMapping("/summary")
    public Mono<Summary> summary() {
        return Mono.fromCallable(this::summaryBlocking)
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    @GetMapping("/usage")
    public Mono<List<UsageView>> usage(
        @RequestParam(name = "limit", defaultValue = "100") int requestedLimit
    ) {
        var limit = Math.max(25, Math.min(requestedLimit, 200));
        return Mono.fromCallable(() -> jdbc.sql("""
                SELECT request_id, api_key_id, provider_id, account_id, model_id,
                       protocol, success, input_tokens, output_tokens,
                       cache_read_tokens, duration_ms, error_class, created_at
                FROM usage_events ORDER BY created_at DESC LIMIT :limit
                """)
            .param("limit", limit).query(AdminObservabilityController::mapUsage).list())
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    private Summary summaryBlocking() {
        var request = jdbc.sql("""
            SELECT COUNT(*) AS request_count,
                   COUNT(*) FILTER (WHERE success) AS success_count,
                   COUNT(*) FILTER (WHERE NOT success) AS failure_count,
                   COALESCE(ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP
                       (ORDER BY duration_ms)), 0)::BIGINT AS p95_duration_ms
            FROM usage_events WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
            """).query((row, ignored) -> new RequestSummary(
                row.getLong("request_count"), row.getLong("success_count"),
                row.getLong("failure_count"), row.getLong("p95_duration_ms"))).single();
        var running = jdbc.sql("""
            SELECT COUNT(*) FROM operation_events WHERE status = 'RUNNING'
            """).query(Long.class).single();
        var failures = jdbc.sql("""
            SELECT provider_id, operation, stage, error_code, COUNT(*) AS failure_count
            FROM operation_events
            WHERE status = 'FAILED'
              AND started_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
            GROUP BY provider_id, operation, stage, error_code
            ORDER BY failure_count DESC, provider_id, operation LIMIT 20
            """).query((row, ignored) -> new FailureGroup(
                row.getString("provider_id"), row.getString("operation"),
                row.getString("stage"), row.getString("error_code"),
                row.getLong("failure_count"))).list();
        return new Summary(
            request.requestCount(), request.successCount(), request.failureCount(),
            request.p95DurationMs(), running, failures);
    }

    private static UsageView mapUsage(ResultSet row, int ignored) throws SQLException {
        return new UsageView(
            row.getString("request_id"), row.getObject("api_key_id", UUID.class),
            row.getString("provider_id"), row.getObject("account_id", UUID.class),
            row.getString("model_id"), row.getString("protocol"),
            row.getBoolean("success"), row.getLong("input_tokens"),
            row.getLong("output_tokens"), row.getLong("cache_read_tokens"),
            row.getLong("duration_ms"), row.getString("error_class"),
            PostgresResultValues.instant(row, "created_at"));
    }

    private record RequestSummary(
        long requestCount,
        long successCount,
        long failureCount,
        long p95DurationMs
    ) {}

    public record Summary(
        long requestCount,
        long successCount,
        long failureCount,
        long p95DurationMs,
        long runningOperations,
        List<FailureGroup> operationFailures
    ) {}

    public record FailureGroup(
        String providerId,
        String operation,
        String stage,
        String errorCode,
        long count
    ) {}

    public record UsageView(
        String requestId,
        UUID apiKeyId,
        String providerId,
        UUID accountId,
        String modelId,
        String protocol,
        boolean success,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long durationMs,
        String errorClass,
        Instant createdAt
    ) {}
}
