--liquibase formatted sql

--changeset any2api:022-001-grok-browser-runtime
INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'grok',
    1,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["/"],
      "discoveryMarkers": {
        "page": ["cli-chat-proxy.grok.com"]
      },
      "capabilities": {},
      "endpointPaths": {
        "chat": "/responses",
        "models": "/models"
      }
    }'::jsonb,
    'bb5a5a704f8f314afed8aece2464465d73fddc7e9c30aa4abf9a798fffcb0170'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
VALUES ('grok', 1, 'IDLE')
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id = 'grok'; DELETE FROM provider_runtime_rule_revisions WHERE provider_id = 'grok' AND revision = 1;
