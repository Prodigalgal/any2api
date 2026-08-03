package com.any2api.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {
    Optional<ApiKeyEntity> findByKeyHashAndEnabledTrue(String keyHash);

    List<ApiKeyEntity> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    @Query("UPDATE ApiKeyEntity key SET key.lastUsedAt = :usedAt WHERE key.id = :id")
    int markUsed(@Param("id") UUID id, @Param("usedAt") Instant usedAt);
}
