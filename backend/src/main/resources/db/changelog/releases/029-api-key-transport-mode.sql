--liquibase formatted sql

--changeset any2api:029-001-api-key-transport-mode
ALTER TABLE api_keys
    ADD COLUMN transport_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD CONSTRAINT ck_api_key_transport_mode
        CHECK (transport_mode IN ('API', 'RUNTIME', 'AUTO'));

COMMENT ON COLUMN api_keys.transport_mode IS
    'Per-key inference transport policy. AUTO prefers API and may fall back to Runtime.';

--rollback ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS ck_api_key_transport_mode, DROP COLUMN IF EXISTS transport_mode;
