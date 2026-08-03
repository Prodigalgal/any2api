--liquibase formatted sql

--changeset any2api:014-001-api-key-feature-grants
CREATE TABLE api_key_feature_grants (
    api_key_id UUID NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    feature VARCHAR(32) NOT NULL,
    PRIMARY KEY (api_key_id, feature),
    CONSTRAINT ck_api_key_feature_grant CHECK (
        feature IN ('MULTIMODAL_INPUT', 'FILE_UPLOADS', 'TOOL_CALLING')
    )
);

-- Existing keys keep their pre-migration behavior. Newly created keys fail closed.
INSERT INTO api_key_feature_grants(api_key_id, feature)
SELECT key.id, feature.name
FROM api_keys key
CROSS JOIN (VALUES
    ('MULTIMODAL_INPUT'),
    ('FILE_UPLOADS'),
    ('TOOL_CALLING')
) AS feature(name)
ON CONFLICT DO NOTHING;

--rollback DROP TABLE IF EXISTS api_key_feature_grants;

--changeset any2api:014-002-registration-job-controls
ALTER TABLE registration_jobs
    ADD COLUMN attempt_interval_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN round_interval_seconds INTEGER NOT NULL DEFAULT 5,
    ADD CONSTRAINT ck_registration_attempt_interval
        CHECK (attempt_interval_seconds BETWEEN 0 AND 3600),
    ADD CONSTRAINT ck_registration_round_interval
        CHECK (round_interval_seconds BETWEEN 0 AND 86400);

--rollback ALTER TABLE registration_jobs DROP CONSTRAINT IF EXISTS ck_registration_round_interval, DROP CONSTRAINT IF EXISTS ck_registration_attempt_interval, DROP COLUMN IF EXISTS round_interval_seconds, DROP COLUMN IF EXISTS attempt_interval_seconds;
