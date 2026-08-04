package com.any2api.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_credentials")
public class AccountCredentialEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "credential_type", nullable = false, length = 64)
    private String credentialType;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "bytea")
    private byte[] encryptedPayload;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] nonce;

    @Column(nullable = false, length = 32)
    private String algorithm;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountCredentialEntity() {
    }

    static AccountCredentialEntity create(UUID accountId, String credentialType) {
        var entity = new AccountCredentialEntity();
        entity.id = UUID.randomUUID();
        entity.accountId = accountId;
        entity.credentialType = credentialType;
        entity.credentialVersion = 0;
        return entity;
    }

    @PrePersist
    void beforeInsert() {
        var now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getCredentialType() {
        return credentialType;
    }

    public byte[] getEncryptedPayload() {
        return encryptedPayload.clone();
    }

    public byte[] getNonce() {
        return nonce.clone();
    }

    public long getCredentialVersion() {
        return credentialVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void replace(byte[] payload, byte[] nonce, int keyVersion, Instant expiresAt) {
        this.encryptedPayload = payload.clone();
        this.nonce = nonce.clone();
        this.algorithm = "AES-256-GCM";
        this.keyVersion = keyVersion;
        this.credentialVersion++;
        this.expiresAt = expiresAt;
    }
}
