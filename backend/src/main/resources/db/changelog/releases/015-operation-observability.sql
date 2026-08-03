--liquibase formatted sql

--changeset any2api:015-001-operation-events
CREATE TABLE operation_events (
    id UUID PRIMARY KEY,
    correlation_id VARCHAR(100) NOT NULL,
    domain VARCHAR(32) NOT NULL,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    operation VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(100) NOT NULL,
    error_code VARCHAR(100),
    error_detail VARCHAR(1200),
    duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_operation_event_domain
        CHECK (domain IN ('REGISTRATION', 'LIFECYCLE', 'INFERENCE')),
    CONSTRAINT ck_operation_event_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);
CREATE INDEX idx_operation_events_aggregate
    ON operation_events(domain, aggregate_id, started_at, attempt);
CREATE INDEX idx_operation_events_correlation
    ON operation_events(correlation_id, started_at);
CREATE INDEX idx_operation_events_provider_outcome
    ON operation_events(provider_id, operation, status, started_at DESC);
CREATE INDEX idx_operation_events_retention ON operation_events(started_at);

ALTER TABLE registration_jobs
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN last_error_stage VARCHAR(100),
    ADD COLUMN last_error_detail VARCHAR(1200),
    ADD COLUMN last_error_correlation_id VARCHAR(100);

--rollback ALTER TABLE registration_jobs DROP COLUMN IF EXISTS last_error_correlation_id, DROP COLUMN IF EXISTS last_error_detail, DROP COLUMN IF EXISTS last_error_stage, DROP COLUMN IF EXISTS last_error_code; DROP TABLE IF EXISTS operation_events;
