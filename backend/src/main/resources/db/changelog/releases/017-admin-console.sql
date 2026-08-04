--liquibase formatted sql

--changeset any2api:017-001-request-content
ALTER TABLE usage_events
    ADD COLUMN input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN output_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX idx_usage_events_api_key_window
    ON usage_events(api_key_id, created_at DESC)
    WHERE api_key_id IS NOT NULL;

--rollback DROP INDEX IF EXISTS idx_usage_events_api_key_window; ALTER TABLE usage_events DROP COLUMN IF EXISTS input_snapshot, DROP COLUMN IF EXISTS output_snapshot;

--changeset any2api:017-002-system-settings
CREATE TABLE system_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    encrypted_value BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    key_version INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--rollback DROP TABLE IF EXISTS system_settings;
