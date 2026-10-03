package com.any2api.auth;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.ProviderTransportMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

record ApiKeySummary(
    UUID id, String name, String prefix, boolean enabled, ProviderTransportMode transportMode,
    Instant lastUsedAt, Instant expiresAt, Instant createdAt, Instant updatedAt
) {
    static ApiKeySummary from(ApiKeyEntity key) {
        return new ApiKeySummary(key.getId(), key.getName(), key.getPrefix(), key.isEnabled(),
            key.getTransportMode(), key.getLastUsedAt(), key.getExpiresAt(), key.getCreatedAt(),
            key.getUpdatedAt());
    }

    static ApiKeySummary map(ResultSet row, int ignored) throws SQLException {
        return new ApiKeySummary(row.getObject("id", UUID.class), row.getString("name"),
            row.getString("prefix"), row.getBoolean("enabled"),
            ProviderTransportMode.valueOf(row.getString("transport_mode")),
            PostgresResultValues.instant(row, "last_used_at"),
            PostgresResultValues.instant(row, "expires_at"),
            PostgresResultValues.instant(row, "created_at"),
            PostgresResultValues.instant(row, "updated_at"));
    }
}
