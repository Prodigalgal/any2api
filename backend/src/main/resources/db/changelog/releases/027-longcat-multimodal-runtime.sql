--liquibase formatted sql

--changeset any2api:027-001-longcat-multimodal-runtime
INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'longcat',
    2,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["longcat.chat"],
      "discoveryMarkers": {
        "page": ["longcat.chat"]
      },
      "capabilities": {},
      "endpointPaths": {
        "session": "/api/v1/session-create",
        "upload": "/api/v1/appendix-upload",
        "chat": "/api/v1/chat-completion-V2"
      }
    }'::jsonb,
    '4b54c846e9dfaa8d8d13e2d26fd7e3386bb3a0b5d77657c8ad1bb9d95b8f7f34'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

-- The upload route is part of the same authenticated LongCat page session as chat.
-- Preserve an operator-owned candidate and let the browser canary govern it.
UPDATE provider_runtime_rule_states
SET last_known_good_revision = active_revision,
    active_revision = 2,
    active_build_id = NULL,
    failure_reason = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_id = 'longcat'
  AND active_revision = 1
  AND candidate_status = 'IDLE';

--rollback UPDATE provider_runtime_rule_states
--rollback SET active_revision = 1, last_known_good_revision = NULL,
--rollback active_build_id = NULL, failure_reason = NULL,
--rollback updated_at = CURRENT_TIMESTAMP
--rollback WHERE provider_id = 'longcat' AND active_revision = 2;
--rollback DELETE FROM provider_runtime_rule_revisions
--rollback WHERE provider_id = 'longcat' AND revision = 2;
