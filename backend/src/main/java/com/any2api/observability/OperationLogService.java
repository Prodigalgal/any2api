package com.any2api.observability;

import com.any2api.persistence.PostgresResultValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogService {
    private final JdbcClient jdbc;

    public OperationLogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page list(Query query) {
        var normalized = query.normalized();
        var total = statement("SELECT COUNT(*) FROM operation_events WHERE ", normalized)
            .query(Long.class).single();
        var items = statement("""
            SELECT id, correlation_id, domain, provider_id, operation, aggregate_type,
                   aggregate_id, account_id, attempt, status, stage, error_code,
                   error_detail, duration_ms, started_at, finished_at
            FROM operation_events WHERE
            """, normalized)
            .param("limit", normalized.size())
            .param("offset", (long) normalized.page() * normalized.size())
            .query(OperationLogService::map).list();
        return new Page(items, total, normalized.page(), normalized.size(),
            total == 0 ? 0 : (int) ((total + normalized.size() - 1) / normalized.size()));
    }

    private JdbcClient.StatementSpec statement(String prefix, Query query) {
        return jdbc.sql(prefix + """
            (:provider = '' OR provider_id = :provider)
              AND (:domain = '' OR domain = :domain)
              AND (:operation = '' OR operation = :operation)
              AND (:status = '' OR status = :status)
              AND (:search = '' OR correlation_id ILIKE '%' || :search || '%'
                   OR aggregate_id ILIKE '%' || :search || '%'
                   OR COALESCE(account_id::text, '') ILIKE '%' || :search || '%'
                   OR COALESCE(error_code, '') ILIKE '%' || :search || '%'
                   OR COALESCE(error_detail, '') ILIKE '%' || :search || '%')
            """ + (prefix.startsWith("SELECT COUNT") ? "" :
                " ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset"))
            .param("provider", query.provider())
            .param("domain", query.domain())
            .param("operation", query.operation())
            .param("status", query.status())
            .param("search", query.search());
    }

    private static OperationEventService.View map(ResultSet row, int ignored) throws SQLException {
        return new OperationEventService.View(
            row.getObject("id", UUID.class), row.getString("correlation_id"),
            row.getString("domain"), row.getString("provider_id"), row.getString("operation"),
            row.getString("aggregate_type"), row.getString("aggregate_id"),
            row.getObject("account_id", UUID.class), row.getInt("attempt"),
            row.getString("status"), row.getString("stage"), row.getString("error_code"),
            row.getString("error_detail"), row.getLong("duration_ms"),
            PostgresResultValues.instant(row, "started_at"),
            PostgresResultValues.instant(row, "finished_at"));
    }

    public record Query(
        String provider, String domain, String operation, String status,
        String search, int page, int size
    ) {
        Query normalized() {
            return new Query(clean(provider), clean(domain).toUpperCase(), clean(operation),
                clean(status).toUpperCase(), clean(search), Math.max(0, page),
                Math.max(10, Math.min(100, size)));
        }

        private static String clean(String value) { return value == null ? "" : value.trim(); }
    }

    public record Page(
        List<OperationEventService.View> items,
        long totalElements,
        int page,
        int size,
        int totalPages
    ) {}
}
