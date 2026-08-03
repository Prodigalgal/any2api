--liquibase formatted sql

--changeset any2api:012-001-account-admin-search
CREATE INDEX idx_accounts_admin_created
    ON accounts(created_at DESC, id);
CREATE INDEX idx_accounts_admin_provider_created
    ON accounts(provider_id, created_at DESC, id);
CREATE INDEX idx_accounts_admin_status_created
    ON accounts(status, enabled, created_at DESC, id);
CREATE INDEX idx_accounts_admin_enabled_created
    ON accounts(enabled, created_at DESC, id);

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_accounts_search_external_id
    ON accounts USING GIN (lower(external_id) gin_trgm_ops);
CREATE INDEX idx_accounts_search_email
    ON accounts USING GIN (lower(email) gin_trgm_ops)
    WHERE email IS NOT NULL;
CREATE INDEX idx_accounts_search_last_error
    ON accounts USING GIN (lower(last_error) gin_trgm_ops)
    WHERE last_error IS NOT NULL;

--rollback DROP INDEX IF EXISTS idx_accounts_search_last_error, idx_accounts_search_email, idx_accounts_search_external_id, idx_accounts_admin_enabled_created, idx_accounts_admin_status_created, idx_accounts_admin_provider_created, idx_accounts_admin_created;
