--liquibase formatted sql

--changeset any2api:007-001-media-assets
CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    content_type VARCHAR(128) NOT NULL,
    content BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_media_assets_expiry ON media_assets(expires_at);
CREATE INDEX idx_media_assets_account ON media_assets(provider_id, account_id);

--rollback DROP TABLE IF EXISTS media_assets;
