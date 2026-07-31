--liquibase formatted sql

--changeset any2api:005-001-provider-installation-state
ALTER TABLE providers
    ADD COLUMN installed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_providers_installed_enabled
    ON providers(installed, enabled);

--rollback DROP INDEX IF EXISTS idx_providers_installed_enabled; ALTER TABLE providers DROP COLUMN IF EXISTS installed;
