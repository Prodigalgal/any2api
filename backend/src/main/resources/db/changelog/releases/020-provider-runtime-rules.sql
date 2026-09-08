--liquibase formatted sql

--changeset any2api:020-001-provider-runtime-rules
INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES ('glm', 'GLM', 'official-browser-z-ai-web-v1', '3')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE provider_runtime_rule_revisions (
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    rules JSONB NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id, revision),
    CONSTRAINT ck_provider_runtime_rules_object CHECK (jsonb_typeof(rules) = 'object')
);

CREATE TABLE provider_runtime_rule_states (
    provider_id VARCHAR(32) PRIMARY KEY REFERENCES providers(id) ON DELETE CASCADE,
    active_revision BIGINT NOT NULL,
    candidate_revision BIGINT,
    last_known_good_revision BIGINT,
    candidate_status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    active_build_id VARCHAR(64),
    candidate_build_id VARCHAR(64),
    failure_reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_runtime_active_revision
        FOREIGN KEY (provider_id, active_revision)
        REFERENCES provider_runtime_rule_revisions(provider_id, revision),
    CONSTRAINT fk_runtime_candidate_revision
        FOREIGN KEY (provider_id, candidate_revision)
        REFERENCES provider_runtime_rule_revisions(provider_id, revision),
    CONSTRAINT fk_runtime_last_good_revision
        FOREIGN KEY (provider_id, last_known_good_revision)
        REFERENCES provider_runtime_rule_revisions(provider_id, revision),
    CONSTRAINT ck_runtime_candidate_status
        CHECK (candidate_status IN ('IDLE', 'PENDING', 'FAILED')),
    CONSTRAINT ck_runtime_candidate_consistency
        CHECK (
            (candidate_revision IS NULL AND candidate_status = 'IDLE')
            OR (candidate_revision IS NOT NULL AND candidate_status IN ('PENDING', 'FAILED'))
        )
);

CREATE INDEX idx_provider_runtime_rule_revisions_created
    ON provider_runtime_rule_revisions(provider_id, created_at DESC, revision DESC);

INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
SELECT id, 1, 1,
    CASE id
        WHEN 'mimo' THEN '{
          "schemaVersion": 1,
          "sessionMaxAgeSeconds": 900,
          "canaryTimeoutSeconds": 60,
          "buildAssetMarkers": ["xiaomimimo.com"],
          "discoveryMarkers": {
            "requestModule": ["/open-apis/bot/chat", "genUploadInfo"]
          },
          "capabilities": {
            "chat": "completions",
            "models": "getConfig"
          },
          "endpointPaths": {
            "chat": "/open-apis/bot/chat",
            "models": "/open-apis/bot/config"
          }
        }'::jsonb
        ELSE '{
          "schemaVersion": 1,
          "sessionMaxAgeSeconds": 900,
          "canaryTimeoutSeconds": 60,
          "buildAssetMarkers": ["/assets/index-"],
          "discoveryMarkers": {
            "newChat": ["/chats/new"],
            "completion": ["X-Signature"],
            "requestContext": ["sortedPayload"],
            "sign": ["5*60*1e3"]
          },
          "capabilities": {},
          "endpointPaths": {
            "chat": "/api/v2/chat/completions",
            "apiBase": "/api/v2"
          }
        }'::jsonb
    END,
    CASE id
        WHEN 'mimo' THEN '0b984326318c13cdc9e07178654fe7549b0e1a8c0d63b51a653e3a898dd86f8e'
        ELSE '56d046751dd8797d906c4c9622cf96193f54ee763ee638ad7cc07fb402a7a997'
    END
FROM providers
WHERE id IN ('mimo', 'glm');

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
SELECT provider_id, revision, 'IDLE'
FROM provider_runtime_rule_revisions
WHERE revision = 1 AND provider_id IN ('mimo', 'glm');

--rollback DROP INDEX IF EXISTS idx_provider_runtime_rule_revisions_created; DROP TABLE IF EXISTS provider_runtime_rule_states; DROP TABLE IF EXISTS provider_runtime_rule_revisions;
