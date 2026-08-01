--liquibase formatted sql

--changeset any2api:011-001-registration-backoff-streak
ALTER TABLE registration_jobs
    ADD COLUMN consecutive_failure_batches INTEGER NOT NULL DEFAULT 0;

--rollback ALTER TABLE registration_jobs DROP COLUMN IF EXISTS consecutive_failure_batches;
