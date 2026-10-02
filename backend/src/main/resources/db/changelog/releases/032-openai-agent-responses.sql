--liquibase formatted sql

--changeset any2api:032-001-owned-responses
--comment: Business version 0.24.0, additive Responses resources and native continuation ownership.
CREATE TABLE gateway_responses (
    response_id VARCHAR(100) PRIMARY KEY,
    owner_scope VARCHAR(80) NOT NULL,
    api_key_id UUID REFERENCES api_keys(id) ON DELETE CASCADE,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    model_id VARCHAR(255) NOT NULL,
    input_items JSONB NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_gateway_responses_owner ON gateway_responses(owner_scope, response_id);
CREATE INDEX idx_gateway_responses_expiry ON gateway_responses(expires_at);
ALTER TABLE provider_response_states ADD COLUMN api_key_id UUID REFERENCES api_keys(id) ON DELETE CASCADE;

--rollback ALTER TABLE provider_response_states DROP COLUMN api_key_id; DROP TABLE gateway_responses;
