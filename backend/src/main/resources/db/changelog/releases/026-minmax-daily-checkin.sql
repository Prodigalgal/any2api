--liquibase formatted sql

--changeset any2api:026-001-minmax-daily-checkin
INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'minmax',
    2,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["agent.minimax.io"],
      "discoveryMarkers": {
        "page": ["agent.minimax.io"],
        "requestModule": ["x-signature", "hasSearchParamsPath"]
      },
      "capabilities": {},
      "endpointPaths": {
        "models": "/archon/api/v1/config",
        "agents": "/archon/api/v1/agent?limit=20",
        "filesPolicy": "/v1/api/files/request_policy",
        "filesCallback": "/v1/api/files/policy_callback",
        "signinStatus": "/minimax-cloud/api/v1/signin/status",
        "signinClaim": "/minimax-cloud/api/v1/signin/claim"
      }
    }'::jsonb,
    '7cbe66da9aa6ea5b92078ab1aba249330115849c387075a14d28b85d61c1f888'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

-- The new paths are verified against the current official Web bundle. Preserve an
-- operator-owned candidate, if one exists, and let the runtime canary govern it.
UPDATE provider_runtime_rule_states
SET last_known_good_revision = active_revision,
    active_revision = 2,
    active_build_id = NULL,
    failure_reason = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_id = 'minmax'
  AND active_revision = 1
  AND candidate_status = 'IDLE';

--rollback UPDATE provider_runtime_rule_states
--rollback SET active_revision = 1, last_known_good_revision = NULL,
--rollback active_build_id = NULL, failure_reason = NULL,
--rollback updated_at = CURRENT_TIMESTAMP
--rollback WHERE provider_id = 'minmax' AND active_revision = 2;
--rollback DELETE FROM provider_runtime_rule_revisions
--rollback WHERE provider_id = 'minmax' AND revision = 2;
