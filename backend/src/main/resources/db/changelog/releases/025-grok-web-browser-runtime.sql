--liquibase formatted sql

--changeset any2api:025-001-grok-web-browser-runtime
INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES ('grok_web', 'Grok Web', 'official-browser-grok-web-v2', '2')
ON CONFLICT (id) DO NOTHING;

INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'grok_web',
    1,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["grok.com"],
      "discoveryMarkers": {
        "page": ["grok.com"],
        "gateway": ["/ws/mgw/"]
      },
      "capabilities": {
        "gateway": "conversation.attached"
      },
      "endpointPaths": {
        "session": "/api/auth/session",
        "gateway": "/ws/mgw/",
        "models": "/api/auth/session"
      }
    }'::jsonb,
    'ea8f8bc71bdb1cb8db4d1306a7d43b399a71d82a5d8a2d0c2bc8cb1d5f6d6c90'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
VALUES ('grok_web', 1, 'IDLE')
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id = 'grok_web'; DELETE FROM provider_runtime_rule_revisions WHERE provider_id = 'grok_web' AND revision = 1; DELETE FROM providers WHERE id = 'grok_web';
