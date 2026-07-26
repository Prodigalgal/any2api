package com.any2api.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountStatus status = AccountStatus.PENDING;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "max_concurrency", nullable = false)
    private int maxConcurrency = 1;

    private int priority;
    private int weight = 1;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getProviderId() {
        return providerId;
    }

    public AccountStatus getStatus() {
        return status;
    }
}

