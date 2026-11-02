--liquibase formatted sql

--changeset any2api:036-001-input-image-files
--comment: Business version 1.8.0; owned expiring raw images, provider-native files remain account-bound.
CREATE TABLE input_image_files (
    file_id VARCHAR(40) PRIMARY KEY,
    owner_scope VARCHAR(80) NOT NULL,
    api_key_id UUID REFERENCES api_keys(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    filename VARCHAR(200) NOT NULL,
    media_type VARCHAR(40) NOT NULL CHECK(media_type IN ('image/jpeg','image/png','image/webp')),
    sha256 VARCHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    byte_length INTEGER NOT NULL CHECK(byte_length>0 AND byte_length<=8388608),
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(owner_scope,idempotency_key),
    CHECK(octet_length(content)=byte_length)
);
CREATE INDEX idx_input_image_files_expiry ON input_image_files(expires_at);
--rollback DROP TABLE input_image_files;
