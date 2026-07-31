package com.any2api.protocol.state;

import com.any2api.persistence.PostgresResultValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ProviderResponseStateStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ProviderResponseStateStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Optional<ResponseState> find(String providerId, String responseId) {
        if (responseId == null || responseId.isBlank()) return Optional.empty();
        return jdbc.sql("""
            SELECT response_id, provider_id, account_id, state, expires_at
            FROM provider_response_states
            WHERE response_id = :responseId AND provider_id = :providerId
              AND expires_at > CURRENT_TIMESTAMP
            """)
            .param("responseId", responseId.trim())
            .param("providerId", providerId)
            .query(this::map)
            .optional();
    }

    @Transactional
    public void save(
        String responseId,
        String providerId,
        UUID accountId,
        JsonNode state,
        Duration ttl
    ) {
        var now = Instant.now();
        jdbc.sql("""
            INSERT INTO provider_response_states(
                response_id, provider_id, account_id, state, expires_at, created_at, updated_at)
            VALUES (:responseId, :providerId, :accountId, CAST(:state AS JSONB),
                    :expiresAt, :now, :now)
            ON CONFLICT (response_id) DO UPDATE SET
                provider_id = EXCLUDED.provider_id,
                account_id = EXCLUDED.account_id,
                state = EXCLUDED.state,
                expires_at = EXCLUDED.expires_at,
                updated_at = EXCLUDED.updated_at
            """)
            .param("responseId", responseId)
            .param("providerId", providerId)
            .param("accountId", accountId)
            .param("state", mapper.writeValueAsString(state))
            .param("expiresAt", PostgresResultValues.timestamp(now.plus(ttl)))
            .param("now", PostgresResultValues.timestamp(now))
            .update();
    }

    @Transactional
    public boolean delete(String providerId, String responseId) {
        return jdbc.sql("""
            DELETE FROM provider_response_states
            WHERE provider_id = :providerId AND response_id = :responseId
            """)
            .param("providerId", providerId)
            .param("responseId", responseId)
            .update() > 0;
    }

    private ResponseState map(ResultSet row, int ignored) throws SQLException {
        return new ResponseState(
            row.getString("response_id"),
            row.getString("provider_id"),
            row.getObject("account_id", UUID.class),
            mapper.readTree(row.getString("state")),
            PostgresResultValues.instant(row, "expires_at"));
    }

    public record ResponseState(
        String responseId,
        String providerId,
        UUID accountId,
        JsonNode state,
        Instant expiresAt
    ) {}
}
