--liquibase formatted sql

--changeset any2api:001-001-foundation
CREATE TABLE providers (
    id VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    adapter_version VARCHAR(64) NOT NULL,
    request_schema_version VARCHAR(32) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE models (
    id VARCHAR(255) PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    upstream_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    catalog_version BIGINT NOT NULL DEFAULT 0,
    fetched_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_models_provider_upstream UNIQUE (provider_id, upstream_id)
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    external_id VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    status VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrency INTEGER NOT NULL DEFAULT 1 CHECK (max_concurrency > 0),
    priority INTEGER NOT NULL DEFAULT 0,
    weight INTEGER NOT NULL DEFAULT 1 CHECK (weight >= 0),
    cooldown_until TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_accounts_provider_external UNIQUE (provider_id, external_id)
);
CREATE INDEX idx_accounts_provider_status ON accounts(provider_id, status, enabled);
CREATE INDEX idx_accounts_expiry ON accounts(expires_at) WHERE enabled = TRUE;

CREATE TABLE account_credentials (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    credential_type VARCHAR(64) NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    key_version INTEGER NOT NULL,
    credential_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_credentials_account_type UNIQUE (account_id, credential_type)
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    prefix VARCHAR(24) NOT NULL,
    key_hash VARCHAR(128) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    quota JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    session_key VARCHAR(255) NOT NULL,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    model_id VARCHAR(255) NOT NULL,
    account_id UUID REFERENCES accounts(id),
    upstream_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    context JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sessions_tenant_key UNIQUE (tenant_id, session_key)
);
CREATE INDEX idx_sessions_expiry ON sessions(expires_at);

CREATE TABLE responses (
    id VARCHAR(100) PRIMARY KEY,
    tenant_id UUID,
    session_id UUID REFERENCES sessions(id),
    status VARCHAR(32) NOT NULL,
    response JSONB NOT NULL,
    input JSONB NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_responses_expiry ON responses(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE registration_jobs (
    id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    requested INTEGER NOT NULL DEFAULT 1,
    target INTEGER NOT NULL DEFAULT 1,
    concurrency INTEGER NOT NULL DEFAULT 1,
    request JSONB NOT NULL DEFAULT '{}'::jsonb,
    result JSONB,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_registration_jobs_status ON registration_jobs(status, created_at);

CREATE TABLE scheduled_actions (
    id UUID PRIMARY KEY,
    handler VARCHAR(100) NOT NULL,
    provider_id VARCHAR(32),
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    action_family VARCHAR(100) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    due_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    generation BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMPTZ,
    last_error_class VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_scheduled_actions_due_pending
    ON scheduled_actions(priority DESC, due_at, id)
    WHERE status = 'PENDING';

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(available_at, id) WHERE status = 'PENDING';

CREATE TABLE usage_events (
    id UUID PRIMARY KEY,
    request_id VARCHAR(100) NOT NULL UNIQUE,
    api_key_id UUID,
    provider_id VARCHAR(32) NOT NULL,
    account_id UUID,
    model_id VARCHAR(255) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    success BOOLEAN NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_class VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_usage_events_created ON usage_events(created_at DESC);

INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES
    ('grok', 'Grok', 'remote-bridge-v1', '1'),
    ('mimo', 'MiMo', 'remote-bridge-v1', '1'),
    ('qwen', 'Qwen', 'remote-bridge-v1', '1'),
    ('longcat', 'LongCat', 'remote-bridge-v1', '1');

--rollback DROP TABLE IF EXISTS usage_events, outbox_events, scheduled_actions, registration_jobs, responses, sessions, api_keys, account_credentials, accounts, models, providers CASCADE;

