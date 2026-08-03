package com.any2api.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
final class ApiKeyGrantStore {
    private static final String GRANT_QUERY = """
        SELECT 1 AS kind, provider_id, NULL::VARCHAR AS value, all_models
        FROM api_key_provider_grants
        WHERE api_key_id = :apiKeyId
        UNION ALL
        SELECT 2 AS kind, provider_id, model_upstream_id AS value, FALSE AS all_models
        FROM api_key_model_grants
        WHERE api_key_id = :apiKeyId
        UNION ALL
        SELECT 3 AS kind, NULL::VARCHAR AS provider_id, protocol AS value, FALSE AS all_models
        FROM api_key_protocol_grants
        WHERE api_key_id = :apiKeyId
        ORDER BY kind, provider_id, value
        """;

    private final JdbcClient jdbc;

    ApiKeyGrantStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void replace(
        UUID apiKeyId,
        Map<String, ApiKeyProviderScope> providerScopes,
        Set<ApiKeyProtocol> protocols
    ) {
        jdbc.sql("DELETE FROM api_key_protocol_grants WHERE api_key_id = :apiKeyId")
            .param("apiKeyId", apiKeyId).update();
        jdbc.sql("DELETE FROM api_key_provider_grants WHERE api_key_id = :apiKeyId")
            .param("apiKeyId", apiKeyId).update();

        providerScopes.values().forEach(scope -> {
            jdbc.sql("""
                INSERT INTO api_key_provider_grants(api_key_id, provider_id, all_models)
                VALUES (:apiKeyId, :providerId, :allModels)
                """)
                .param("apiKeyId", apiKeyId)
                .param("providerId", scope.providerId())
                .param("allModels", scope.allModels())
                .update();
            scope.models().forEach(model -> jdbc.sql("""
                INSERT INTO api_key_model_grants(api_key_id, provider_id, model_upstream_id)
                VALUES (:apiKeyId, :providerId, :model)
                """)
                .param("apiKeyId", apiKeyId)
                .param("providerId", scope.providerId())
                .param("model", model)
                .update());
        });
        protocols.forEach(protocol -> jdbc.sql("""
            INSERT INTO api_key_protocol_grants(api_key_id, protocol)
            VALUES (:apiKeyId, :protocol)
            """)
            .param("apiKeyId", apiKeyId)
            .param("protocol", protocol.name())
            .update());
    }

    ApiKeyGrant read(ApiKeyEntity key) {
        var providers = new LinkedHashMap<String, MutableProviderScope>();
        var protocols = new LinkedHashSet<ApiKeyProtocol>();
        jdbc.sql(GRANT_QUERY)
            .param("apiKeyId", key.getId())
            .query((result, rowNumber) -> new GrantRow(
                result.getInt("kind"),
                result.getString("provider_id"),
                result.getString("value"),
                result.getBoolean("all_models")))
            .list()
            .forEach(row -> {
                if (row.kind() == 1) {
                    providers.put(row.providerId(),
                        new MutableProviderScope(row.allModels(), new LinkedHashSet<>()));
                } else if (row.kind() == 2) {
                    var scope = providers.get(row.providerId());
                    if (scope != null && !scope.allModels()) scope.models().add(row.value());
                } else if (row.kind() == 3) {
                    try {
                        protocols.add(ApiKeyProtocol.valueOf(row.value()));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown persisted permissions fail closed.
                    }
                }
            });

        var immutable = new LinkedHashMap<String, ApiKeyProviderScope>();
        providers.forEach((providerId, scope) -> {
            if (scope.allModels()) {
                immutable.put(providerId, ApiKeyProviderScope.allModels(providerId));
            } else if (!scope.models().isEmpty()) {
                immutable.put(providerId,
                    ApiKeyProviderScope.selectedModels(providerId, scope.models()));
            }
        });
        return new ApiKeyGrant(
            key.getId(), key.getName(), immutable, protocols, key.getExpiresAt(), false);
    }

    private record GrantRow(int kind, String providerId, String value, boolean allModels) {}

    private record MutableProviderScope(boolean allModels, LinkedHashSet<String> models) {}
}
