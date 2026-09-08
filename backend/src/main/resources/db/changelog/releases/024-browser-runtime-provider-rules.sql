--liquibase formatted sql

--changeset any2api:024-001-browser-runtime-provider-rules
INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES
    ('deepseek', 'DeepSeek', 'native-deepseek-web-v2.3', '2'),
    ('minmax', 'MinMax', 'native-minmax-agent-web-v1', '2')
ON CONFLICT (id) DO NOTHING;

INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES
(
    'qwen', 1, 1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["qwen-chat-fe"],
      "discoveryMarkers": {
        "page": ["chat.qwen.ai"],
        "models": ["/api/v2/models/"]
      },
      "capabilities": {},
      "endpointPaths": {
        "models": "/api/v2/models/",
        "session": "/api/v2/chats/new",
        "upload": "/api/v2/files/getstsToken",
        "chat": "/api/v2/chat/completions"
      }
    }'::jsonb,
    '8b1d8d620f7f3a9d474d599ac9d1d914b4b1f8da9122ec7c3cc4b2cc4d4b3c91'
),
(
    'deepseek', 1, 1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["deepseek"],
      "discoveryMarkers": {
        "page": ["chat.deepseek.com"],
        "settings": ["/api/v0/client/settings"]
      },
      "capabilities": {},
      "endpointPaths": {
        "models": "/api/v0/client/settings",
        "session": "/api/v0/chat_session/create",
        "pow": "/api/v0/chat/create_pow_challenge",
        "completion": "/api/v0/chat/completion"
      }
    }'::jsonb,
    'b37351bb1f2141454ec35a262df78ab93c28b4ffac8d0d2c3fc0baf429a1a3a4'
),
(
    'minmax', 1, 1,
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
        "filesCallback": "/v1/api/files/policy_callback"
      }
    }'::jsonb,
    '5de10251cd30e7b5fb92c89ff69ee57c7cda1360c41b093b59e4c4f61d24a5b'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
SELECT provider_id, 1, 'IDLE'
FROM provider_runtime_rule_revisions
WHERE provider_id IN ('qwen', 'deepseek', 'minmax') AND revision = 1
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id IN ('qwen', 'deepseek', 'minmax'); DELETE FROM provider_runtime_rule_revisions WHERE provider_id IN ('qwen', 'deepseek', 'minmax') AND revision = 1; DELETE FROM providers WHERE id IN ('deepseek', 'minmax');
