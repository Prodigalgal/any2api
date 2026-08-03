package com.any2api.observability;

import com.any2api.persistence.PostgresResultValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public final class OperationEventService {
    private static final Logger log = LoggerFactory.getLogger(OperationEventService.class);
    private static final Pattern DATA = Pattern.compile(
        "data:[^;\\s]+;base64,[A-Za-z0-9+/=]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
        "(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}(?![\\w.-])");
    private static final Pattern QUERY_URL = Pattern.compile("https?://[^\\s?#]+\\?[^\\s#]+");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)([\"']?(?:password|token|authorization|cookie|jwt|secret)"
            + "[\"']?\\s*[:=]\\s*[\"']?)[^,}'\"\\r\\n]+");

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public OperationEventService(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public Started start(
        String domain,
        String providerId,
        String operation,
        OperationContext context
    ) {
        var started = new Started(
            UUID.randomUUID(), context.correlationId(), domain, providerId, operation,
            context.aggregateType(), context.aggregateId(), context.attempt(), Instant.now());
        jdbc.sql("""
            INSERT INTO operation_events(
                id, correlation_id, domain, provider_id, operation,
                aggregate_type, aggregate_id, attempt, status, stage, started_at)
            VALUES (:id, :correlationId, :domain, :providerId, :operation,
                    :aggregateType, :aggregateId, :attempt, 'RUNNING', 'started', :startedAt)
            """)
            .param("id", started.id())
            .param("correlationId", started.correlationId())
            .param("domain", started.domain())
            .param("providerId", started.providerId())
            .param("operation", started.operation())
            .param("aggregateType", started.aggregateType())
            .param("aggregateId", started.aggregateId())
            .param("attempt", started.attempt())
            .param("startedAt", started.startedAt())
            .update();
        log.info(
            "operation_started correlation_id={} domain={} provider={} operation={} aggregate_type={} aggregate_id={} attempt={}",
            started.correlationId(), domain, providerId, operation,
            context.aggregateType(), context.aggregateId(), context.attempt());
        return started;
    }

    public void linkAccount(Started started, UUID accountId) {
        jdbc.sql("UPDATE operation_events SET account_id = :accountId WHERE id = :id")
            .param("accountId", accountId).param("id", started.id()).update();
    }

    public void succeed(Started started, String stage) {
        finish(started, "SUCCEEDED", stage, null);
    }

    public Failure fail(Started started, Throwable error) {
        var failure = describe(error);
        finish(started, "FAILED", failure.stage(), failure);
        return failure;
    }

    public Failure fail(Started started, String code, String stage, String detail) {
        var failure = new Failure(code, stage, sanitize(detail), "OperationFailure");
        finish(started, "FAILED", stage, failure);
        return failure;
    }

    public Failure describe(Throwable error) {
        if (error instanceof StructuredOperationFailure structured) {
            return new Failure(
                normalized(structured.errorCode(), "operation_failed"),
                normalized(structured.stage(), "operation"),
                sanitize(structured.detail()),
                error.getClass().getSimpleName());
        }
        return new Failure(
            error.getClass().getSimpleName(), "application",
            sanitize(error.getMessage()), error.getClass().getSimpleName());
    }

    public List<View> list(String domain, String aggregateId) {
        return jdbc.sql("""
            SELECT id, correlation_id, domain, provider_id, operation, aggregate_type,
                   aggregate_id, account_id, attempt, status, stage, error_code,
                   error_detail, duration_ms, started_at, finished_at
            FROM operation_events
            WHERE domain = :domain AND aggregate_id = :aggregateId
            ORDER BY started_at, attempt, id
            LIMIT 500
            """)
            .param("domain", domain).param("aggregateId", aggregateId)
            .query(OperationEventService::map).list();
    }

    private void finish(Started started, String status, String stage, Failure failure) {
        var finishedAt = Instant.now();
        var duration = Math.max(0, Duration.between(started.startedAt(), finishedAt).toMillis());
        var updated = jdbc.sql("""
            UPDATE operation_events
            SET status = :status, stage = :stage, error_code = :errorCode,
                error_detail = :errorDetail, duration_ms = :duration,
                finished_at = :finishedAt
            WHERE id = :id AND status = 'RUNNING'
            """)
            .param("status", status).param("stage", normalized(stage, "completed"))
            .param("errorCode", failure == null ? null : failure.code())
            .param("errorDetail", failure == null ? null : failure.detail())
            .param("duration", duration).param("finishedAt", finishedAt)
            .param("id", started.id()).update();
        if (updated != 1) return;
        Timer.builder("any2api.operation.duration")
            .tag("domain", started.domain())
            .tag("provider", started.providerId())
            .tag("operation", started.operation())
            .tag("outcome", status.toLowerCase())
            .register(meters)
            .record(Duration.ofMillis(duration));
        log.atLevel(failure == null ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
            .log(
                "operation_finished correlation_id={} domain={} provider={} operation={} aggregate_id={} attempt={} status={} stage={} error_code={} duration_ms={}",
                started.correlationId(), started.domain(), started.providerId(),
                started.operation(), started.aggregateId(), started.attempt(), status,
                normalized(stage, "completed"), failure == null ? "" : failure.code(), duration);
    }

    static String sanitize(String raw) {
        var value = raw == null ? "" : raw;
        value = DATA.matcher(value).replaceAll("<embedded-data>");
        value = EMAIL.matcher(value).replaceAll("<email>");
        value = QUERY_URL.matcher(value).replaceAll("<url-with-query>");
        value = SECRET.matcher(value).replaceAll("$1<redacted>");
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() <= 1200 ? value : value.substring(0, 1200);
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static View map(ResultSet row, int ignored) throws SQLException {
        return new View(
            row.getObject("id", UUID.class), row.getString("correlation_id"),
            row.getString("domain"), row.getString("provider_id"),
            row.getString("operation"), row.getString("aggregate_type"),
            row.getString("aggregate_id"), row.getObject("account_id", UUID.class),
            row.getInt("attempt"), row.getString("status"), row.getString("stage"),
            row.getString("error_code"), row.getString("error_detail"),
            row.getLong("duration_ms"), PostgresResultValues.instant(row, "started_at"),
            PostgresResultValues.instant(row, "finished_at"));
    }

    public record Started(
        UUID id, String correlationId, String domain, String providerId, String operation,
        String aggregateType, String aggregateId, int attempt, Instant startedAt
    ) {}

    public record Failure(String code, String stage, String detail, String errorClass) {}

    public record View(
        UUID id, String correlationId, String domain, String providerId, String operation,
        String aggregateType, String aggregateId, UUID accountId, int attempt, String status,
        String stage, String errorCode, String errorDetail, long durationMs,
        Instant startedAt, Instant finishedAt
    ) {}
}
