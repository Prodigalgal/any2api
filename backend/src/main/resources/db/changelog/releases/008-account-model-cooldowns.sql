--liquibase formatted sql

--changeset any2api:008-001-account-model-cooldowns
CREATE TABLE account_model_cooldowns (
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    model_id VARCHAR(255) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    cooldown_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, provider_id, model_id)
);
CREATE INDEX idx_account_model_cooldowns_lookup
    ON account_model_cooldowns(provider_id, model_id, cooldown_until);

--rollback DROP TABLE IF EXISTS account_model_cooldowns;
