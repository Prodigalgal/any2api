--liquibase formatted sql

--changeset any2api:003-001-registration-progress
ALTER TABLE registration_jobs
    ADD COLUMN success_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_error_class VARCHAR(100),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_registration_jobs_claim
    ON registration_jobs(next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING') AND cancel_requested = FALSE;

--rollback DROP INDEX IF EXISTS idx_registration_jobs_claim; ALTER TABLE registration_jobs DROP COLUMN IF EXISTS next_attempt_at, DROP COLUMN IF EXISTS last_error_class, DROP COLUMN IF EXISTS cancel_requested, DROP COLUMN IF EXISTS failure_count, DROP COLUMN IF EXISTS success_count;
