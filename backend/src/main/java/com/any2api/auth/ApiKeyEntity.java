package com.any2api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 24)
    private String prefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 128)
    private String keyHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiKeyEntity() {
    }

    static ApiKeyEntity create(
        String name,
        String prefix,
        String keyHash,
        Instant expiresAt
    ) {
        var key = new ApiKeyEntity();
        key.id = UUID.randomUUID();
        key.name = name;
        key.prefix = prefix;
        key.keyHash = keyHash;
        key.expiresAt = expiresAt;
        return key;
    }

    @PrePersist
    void beforeInsert() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public String getKeyHash() { return keyHash; }
    public boolean isEnabled() { return enabled; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean value) {
        enabled = value;
    }
}
