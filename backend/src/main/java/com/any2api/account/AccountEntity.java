package com.any2api.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "success_count", nullable = false)
    private long successCount;

    @Column(name = "failure_count", nullable = false)
    private long failureCount;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_error")
    private String lastError;

    protected AccountEntity() {
    }

    public static AccountEntity create(
        String providerId,
        String externalId,
        String email,
        Instant expiresAt,
        Map<String, Object> metadata
    ) {
        var account = new AccountEntity();
        account.id = UUID.randomUUID();
        account.providerId = providerId;
        account.externalId = externalId;
        account.email = email;
        account.expiresAt = expiresAt;
        account.status = AccountStatus.ACTIVE;
        account.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        return account;
    }

    @PrePersist
    void beforeInsert() {
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getEmail() {
        return email;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public int getPriority() {
        return priority;
    }

    public int getWeight() {
        return weight;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Map<String, Object> getMetadata() {
        return Map.copyOf(metadata);
    }

    public long getVersion() {
        return version;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getSuccessCount() { return successCount; }
    public long getFailureCount() { return failureCount; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateProfile(
        String email,
        Instant expiresAt,
        Map<String, Object> metadata,
        Integer priority,
        Integer weight,
        Integer maxConcurrency
    ) {
        this.email = email;
        this.expiresAt = expiresAt;
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        if (priority != null) this.priority = priority;
        if (weight != null) this.weight = Math.max(0, weight);
        if (maxConcurrency != null) this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    public void updateState(AccountStatus status, Boolean enabled) {
        if (status != null) this.status = status;
        if (enabled != null) this.enabled = enabled;
    }

    public void mergeMetadata(Map<String, Object> patch) {
        if (patch != null) this.metadata.putAll(patch);
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed(Instant now) {
        requestCount++;
        lastUsedAt = now;
    }

    public void markSuccess(Instant now) {
        successCount++;
        failureCount = 0;
        lastSuccessAt = now;
        lastError = null;
        cooldownUntil = null;
        status = AccountStatus.ACTIVE;
    }

    public void markFailure(Instant now, String error, Instant cooldown) {
        failureCount++;
        lastFailureAt = now;
        lastError = error == null ? "provider request failed" : error.substring(0, Math.min(error.length(), 4000));
        cooldownUntil = cooldown;
        status = AccountStatus.DEGRADED;
    }
}
