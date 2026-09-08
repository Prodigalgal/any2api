--liquibase formatted sql

--changeset any2api:021-001-grok-console-browser-runtime
INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES ('grok_console', 'Grok Console', 'xai-console-sso-v1', '2')
ON CONFLICT (id) DO NOTHING;

INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'grok_console',
    1,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["/"],
      "discoveryMarkers": {
        "page": ["console.x.ai"]
      },
      "capabilities": {},
      "endpointPaths": {
        "chat": "/v1/responses"
      }
    }'::jsonb,
    'ab3ae937efc4389df52838e09dfa99ebe24785a931a373f27527c539adde2920'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
VALUES ('grok_console', 1, 'IDLE')
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id = 'grok_console'; DELETE FROM provider_runtime_rule_revisions WHERE provider_id = 'grok_console'; DELETE FROM providers WHERE id = 'grok_console';
