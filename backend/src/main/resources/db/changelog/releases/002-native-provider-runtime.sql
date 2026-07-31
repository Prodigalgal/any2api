--liquibase formatted sql

--changeset any2api:002-001-account-runtime
ALTER TABLE accounts ADD COLUMN request_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN success_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN failure_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN last_used_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN last_success_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN last_failure_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN last_error TEXT;

ALTER TABLE models ADD COLUMN catalog_source VARCHAR(32) NOT NULL DEFAULT 'MANIFEST';
ALTER TABLE models ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_accounts_eligible
    ON accounts(provider_id, enabled, status, priority DESC, last_used_at)
    WHERE enabled = TRUE;

--rollback ALTER TABLE models DROP COLUMN IF EXISTS catalog_source, DROP COLUMN IF EXISTS metadata;
--rollback ALTER TABLE accounts DROP COLUMN IF EXISTS request_count, DROP COLUMN IF EXISTS success_count, DROP COLUMN IF EXISTS failure_count, DROP COLUMN IF EXISTS last_used_at, DROP COLUMN IF EXISTS last_success_at, DROP COLUMN IF EXISTS last_failure_at, DROP COLUMN IF EXISTS last_error;
