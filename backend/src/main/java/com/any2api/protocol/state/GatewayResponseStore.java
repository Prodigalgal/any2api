package com.any2api.protocol.state;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.coordination.PostgresAdvisoryLocks;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class GatewayResponseStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final PostgresAdvisoryLocks locks;

    public GatewayResponseStore(JdbcClient jdbc, ObjectMapper mapper, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.locks = locks;
    }

    public record StoredResponse(String id, String providerId, String model, JsonNode input, JsonNode response) {}

    public Optional<StoredResponse> find(String id, String ownerScope) {
        return jdbc.sql("""
            SELECT response_id, provider_id, model_id, input_items, response
            FROM gateway_responses
            WHERE response_id = :id AND owner_scope = :owner AND expires_at > CURRENT_TIMESTAMP
            """).param("id", id).param("owner", ownerScope)
            .query((row, index) -> new StoredResponse(row.getString("response_id"), row.getString("provider_id"),
                row.getString("model_id"), mapper.readTree(row.getString("input_items")),
                mapper.readTree(row.getString("response"))))
            .optional();
    }

    @Transactional
    public void save(String ownerScope, UUID apiKeyId, String provider, String model,
        JsonNode input, JsonNode response, Instant expiresAt, int maxStored) {
        locks.lockTransaction("responses:" + ownerScope);
        var active = jdbc.sql("SELECT COUNT(*) FROM gateway_responses WHERE owner_scope = :owner AND expires_at > CURRENT_TIMESTAMP")
            .param("owner", ownerScope).query(Long.class).single();
        var exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM gateway_responses WHERE response_id = :id AND owner_scope = :owner)")
            .param("id", response.path("id").asText()).param("owner", ownerScope).query(Boolean.class).single();
        if (active >= maxStored && !exists) throw new ResponseStorageLimitException();
        jdbc.sql("""
            INSERT INTO gateway_responses(response_id, owner_scope, api_key_id, provider_id, model_id,
                input_items, response, expires_at)
            VALUES(:id, :owner, :key, :provider, :model, CAST(:input AS JSONB), CAST(:response AS JSONB), :expiry)
            ON CONFLICT(response_id) DO UPDATE SET input_items = EXCLUDED.input_items, response = EXCLUDED.response
            WHERE gateway_responses.owner_scope = EXCLUDED.owner_scope
              AND gateway_responses.provider_id = EXCLUDED.provider_id AND gateway_responses.model_id = EXCLUDED.model_id
              AND gateway_responses.response ->> 'status' = 'in_progress'
            """).param("id", response.path("id").asText()).param("owner", ownerScope).param("key", apiKeyId)
            .param("provider", provider).param("model", model).param("input", mapper.writeValueAsString(input))
            .param("response", mapper.writeValueAsString(response)).param("expiry", PostgresResultValues.timestamp(expiresAt))
            .update();
    }

    public boolean delete(String id, String ownerScope) {
        return jdbc.sql("DELETE FROM gateway_responses WHERE response_id = :id AND owner_scope = :owner AND expires_at > CURRENT_TIMESTAMP")
            .param("id", id).param("owner", ownerScope).update() > 0;
    }

    public void updateProgress(String ownerScope, String provider, String model, JsonNode response) {
        jdbc.sql("""
            UPDATE gateway_responses SET response = CAST(:response AS JSONB)
            WHERE response_id = :id AND owner_scope = :owner AND provider_id = :provider AND model_id = :model
              AND expires_at > CURRENT_TIMESTAMP AND response ->> 'status' = 'in_progress'
            """).param("id", response.path("id").asText()).param("owner", ownerScope)
            .param("provider", provider).param("model", model).param("response", mapper.writeValueAsString(response)).update();
    }
}
