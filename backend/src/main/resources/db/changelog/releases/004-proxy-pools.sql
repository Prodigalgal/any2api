--liquibase formatted sql

--changeset any2api:004-001-proxy-pools
CREATE TABLE proxy_pools (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    mode VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    algorithm VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
    key_version INTEGER NOT NULL DEFAULT 1,
    revision BIGINT NOT NULL DEFAULT 1,
    node_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE provider_proxy_bindings (
    provider_id VARCHAR(32) PRIMARY KEY REFERENCES providers(id) ON DELETE CASCADE,
    proxy_pool_id UUID NOT NULL REFERENCES proxy_pools(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_provider_proxy_bindings_pool ON provider_proxy_bindings(proxy_pool_id);

--rollback DROP TABLE IF EXISTS provider_proxy_bindings; DROP TABLE IF EXISTS proxy_pools;
