--liquibase formatted sql

--changeset any2api:019-001-registration-schedules
CREATE TABLE registration_schedules (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    schedule_type VARCHAR(16) NOT NULL,
    interval_minutes INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_job_id UUID REFERENCES registration_jobs(id) ON DELETE SET NULL,
    job_command JSONB NOT NULL,
    last_error VARCHAR(500),
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_registration_schedule_type
        CHECK (schedule_type IN ('ONCE', 'INTERVAL')),
    CONSTRAINT ck_registration_schedule_interval
        CHECK (
            (schedule_type = 'ONCE' AND interval_minutes IS NULL)
            OR (schedule_type = 'INTERVAL' AND interval_minutes BETWEEN 5 AND 10080)
        ),
    CONSTRAINT ck_registration_schedule_next_run
        CHECK (enabled = FALSE OR next_run_at IS NOT NULL)
);

CREATE INDEX idx_registration_schedules_due
    ON registration_schedules(next_run_at, id)
    WHERE enabled = TRUE;
CREATE INDEX idx_registration_schedules_created
    ON registration_schedules(created_at DESC, id DESC);

CREATE INDEX idx_registration_jobs_created_page
    ON registration_jobs(created_at DESC, id DESC);
CREATE INDEX idx_registration_jobs_provider_created_page
    ON registration_jobs(provider_id, created_at DESC, id DESC);

--rollback DROP INDEX IF EXISTS idx_registration_jobs_provider_created_page; DROP INDEX IF EXISTS idx_registration_jobs_created_page; DROP TABLE IF EXISTS registration_schedules;
