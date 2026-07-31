package com.any2api.media;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetStore {
    UUID save(
        String providerId,
        UUID accountId,
        String contentType,
        byte[] content,
        Duration ttl
    );

    Optional<StoredMediaAsset> find(UUID id);

    record StoredMediaAsset(
        UUID id,
        String contentType,
        byte[] content,
        Instant expiresAt
    ) {
        public StoredMediaAsset {
            content = content.clone();
        }

        @Override public byte[] content() { return content.clone(); }
    }
}
