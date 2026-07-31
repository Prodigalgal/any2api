package com.any2api.media;

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

@Repository
public class PostgresMediaAssetStore implements MediaAssetStore {
    private static final int MAX_ASSET_BYTES = 12 << 20;
    private final JdbcClient jdbc;

    public PostgresMediaAssetStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public UUID save(
        String providerId,
        UUID accountId,
        String contentType,
        byte[] content,
        Duration ttl
    ) {
        if (content.length == 0 || content.length > MAX_ASSET_BYTES) {
            throw new IllegalArgumentException("media asset size must be between 1 byte and 12 MiB");
        }
        var id = UUID.randomUUID();
        var now = Instant.now();
        jdbc.sql("""
            INSERT INTO media_assets(
                id, provider_id, account_id, content_type, content,
                expires_at, created_at)
            VALUES (:id, :providerId, :accountId, :contentType, :content,
                    :expiresAt, :now)
            """)
            .param("id", id)
            .param("providerId", providerId)
            .param("accountId", accountId)
            .param("contentType", contentType)
            .param("content", content)
            .param("expiresAt", PostgresResultValues.timestamp(now.plus(ttl)))
            .param("now", PostgresResultValues.timestamp(now))
            .update();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredMediaAsset> find(UUID id) {
        return jdbc.sql("""
            SELECT id, content_type, content, expires_at
            FROM media_assets
            WHERE id = :id AND expires_at > CURRENT_TIMESTAMP
            """)
            .param("id", id)
            .query(this::map)
            .optional();
    }

    private StoredMediaAsset map(ResultSet row, int ignored) throws SQLException {
        return new StoredMediaAsset(
            row.getObject("id", UUID.class),
            row.getString("content_type"),
            row.getBytes("content"),
            PostgresResultValues.instant(row, "expires_at"));
    }
}
