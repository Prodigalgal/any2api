--liquibase formatted sql

--changeset any2api:023-001-longcat-browser-runtime
INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'longcat',
    1,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["/"],
      "discoveryMarkers": {
        "page": ["longcat.chat"]
      },
      "capabilities": {},
      "endpointPaths": {
        "session": "/api/v1/session-create",
        "chat": "/api/v1/chat-completion-V2"
      }
    }'::jsonb,
    '2f8c304e767ca2f8882496261e370aa091fc20698defe5e0dc13ff18259755db'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
VALUES ('longcat', 1, 'IDLE')
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id = 'longcat'; DELETE FROM provider_runtime_rule_revisions WHERE provider_id = 'longcat' AND revision = 1;
