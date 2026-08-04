package com.any2api.observability;

import com.any2api.persistence.PostgresResultValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RequestLogService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RequestLogService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page list(Query query) {
        var normalized = query.normalized();
        var total = baseQuery("SELECT COUNT(*) FROM usage_events WHERE ", normalized)
            .query(Long.class).single();
        var items = baseQuery("""
            SELECT request_id, api_key_id, provider_id, account_id, model_id, protocol,
                   success, input_tokens, output_tokens, cache_read_tokens, duration_ms,
                   error_class, attempt, request_kind, usage_source, queue_ms,
                   account_acquire_ms, ttfb_ms, generation_ms, created_at
            FROM usage_events WHERE
            """, normalized)
            .param("limit", normalized.size())
            .param("offset", (long) normalized.page() * normalized.size())
            .query(RequestLogService::mapSummary).list();
        return new Page(items, total, normalized.page(), normalized.size(),
            total == 0 ? 0 : (int) ((total + normalized.size() - 1) / normalized.size()));
    }

    @Transactional(readOnly = true)
    public Detail get(String requestId, int attempt) {
        return jdbc.sql("""
            SELECT request_id, api_key_id, provider_id, account_id, model_id, protocol,
                   success, input_tokens, output_tokens, cache_read_tokens, duration_ms,
                   error_class, attempt, request_kind, usage_source, queue_ms,
                   account_acquire_ms, ttfb_ms, generation_ms, created_at,
                   input_snapshot, output_snapshot
            FROM usage_events WHERE request_id = :requestId AND attempt = :attempt
            """)
            .param("requestId", requestId).param("attempt", attempt)
            .query(this::mapDetail).optional()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown request attempt: " + requestId + ":" + attempt));
    }

    private JdbcClient.StatementSpec baseQuery(String prefix, Query query) {
        return jdbc.sql(prefix + """
            (:provider = '' OR provider_id = :provider)
              AND (:model = '' OR model_id = :model)
              AND (:apiKey = '' OR COALESCE(api_key_id::text, '') = :apiKey)
              AND (:kind = '' OR request_kind = :kind)
              AND (:status = ''
                   OR (:status = 'SUCCEEDED' AND success = TRUE)
                   OR (:status = 'FAILED' AND success = FALSE))
              AND (:search = '' OR request_id ILIKE '%' || :search || '%'
                   OR model_id ILIKE '%' || :search || '%'
                   OR COALESCE(error_class, '') ILIKE '%' || :search || '%'
                   OR COALESCE(account_id::text, '') ILIKE '%' || :search || '%')
            """ + (prefix.startsWith("SELECT COUNT") ? "" :
                " ORDER BY created_at DESC, request_id, attempt DESC LIMIT :limit OFFSET :offset"))
            .param("provider", query.provider())
            .param("model", query.model())
            .param("apiKey", query.apiKeyId())
            .param("kind", query.requestKind())
            .param("status", query.status())
            .param("search", query.search());
    }

    private Detail mapDetail(ResultSet row, int ignored) throws SQLException {
        var summary = mapSummary(row, ignored);
        return new Detail(summary, json(row.getString("input_snapshot")),
            json(row.getString("output_snapshot")));
    }

    private JsonNode json(String value) {
        return value == null ? mapper.createObjectNode() : mapper.readTree(value);
    }

    private static Summary mapSummary(ResultSet row, int ignored) throws SQLException {
        return new Summary(
            row.getString("request_id"), row.getObject("api_key_id", UUID.class),
            row.getString("provider_id"), row.getObject("account_id", UUID.class),
            row.getString("model_id"), row.getString("protocol"), row.getBoolean("success"),
            row.getLong("input_tokens"), row.getLong("output_tokens"),
            row.getLong("cache_read_tokens"), row.getLong("duration_ms"),
            row.getString("error_class"), row.getInt("attempt"),
            row.getString("request_kind"), row.getString("usage_source"),
            row.getLong("queue_ms"), row.getLong("account_acquire_ms"),
            row.getLong("ttfb_ms"), row.getLong("generation_ms"),
            PostgresResultValues.instant(row, "created_at"));
    }

    public record Query(
        String provider, String model, String apiKeyId, String requestKind,
        String status, String search, int page, int size
    ) {
        Query normalized() {
            return new Query(clean(provider), clean(model), clean(apiKeyId), clean(requestKind),
                clean(status).toUpperCase(), clean(search), Math.max(0, page),
                Math.max(10, Math.min(100, size)));
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Page(List<Summary> items, long totalElements, int page, int size, int totalPages) {}

    public record Detail(Summary request, JsonNode input, JsonNode output) {}

    public record Summary(
        String requestId, UUID apiKeyId, String providerId, UUID accountId, String modelId,
        String protocol, boolean success, long inputTokens, long outputTokens,
        long cacheReadTokens, long durationMs, String errorClass, int attempt,
        String requestKind, String usageSource, long queueMs, long accountAcquireMs,
        long ttfbMs, long generationMs, Instant createdAt
    ) {}
}
