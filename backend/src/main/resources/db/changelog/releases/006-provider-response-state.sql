--liquibase formatted sql

--changeset any2api:006-001-provider-response-state
CREATE TABLE provider_response_states (
    response_id VARCHAR(100) PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    state JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_provider_response_states_account
    ON provider_response_states(provider_id, account_id);
CREATE INDEX idx_provider_response_states_expiry
    ON provider_response_states(expires_at);

--rollback DROP TABLE IF EXISTS provider_response_states;
