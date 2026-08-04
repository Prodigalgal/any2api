--liquibase formatted sql

--changeset any2api:016-001-inference-stage-telemetry
ALTER TABLE usage_events DROP CONSTRAINT usage_events_request_id_key;
ALTER TABLE usage_events
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN request_kind VARCHAR(24) NOT NULL DEFAULT 'INFERENCE',
    ADD COLUMN usage_source VARCHAR(16) NOT NULL DEFAULT 'ESTIMATED',
    ADD COLUMN queue_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN account_acquire_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN ttfb_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN generation_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE usage_events
    ADD CONSTRAINT uk_usage_events_request_attempt UNIQUE (request_id, attempt);
CREATE INDEX idx_usage_events_model_window
    ON usage_events(provider_id, model_id, created_at DESC);

--rollback DROP INDEX IF EXISTS idx_usage_events_model_window; ALTER TABLE usage_events DROP CONSTRAINT IF EXISTS uk_usage_events_request_attempt; ALTER TABLE usage_events DROP COLUMN IF EXISTS attempt, DROP COLUMN IF EXISTS request_kind, DROP COLUMN IF EXISTS usage_source, DROP COLUMN IF EXISTS queue_ms, DROP COLUMN IF EXISTS account_acquire_ms, DROP COLUMN IF EXISTS ttfb_ms, DROP COLUMN IF EXISTS generation_ms; ALTER TABLE usage_events ADD CONSTRAINT usage_events_request_id_key UNIQUE (request_id);

--changeset any2api:016-002-model-probe-results
CREATE TABLE model_probe_results (
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    model_id VARCHAR(255) NOT NULL,
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL,
    error_class VARCHAR(100),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    probed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id, model_id)
);
CREATE INDEX idx_model_probe_results_status
    ON model_probe_results(status, probed_at DESC);

--rollback DROP TABLE IF EXISTS model_probe_results;
