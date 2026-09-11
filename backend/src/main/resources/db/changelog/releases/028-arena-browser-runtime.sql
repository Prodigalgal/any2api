--liquibase formatted sql

--changeset any2api:028-001-arena-browser-runtime
INSERT INTO providers(id, display_name, adapter_version, request_schema_version)
VALUES ('arena', 'Arena', 'official-browser-arena-v1', '3')
ON CONFLICT (id) DO NOTHING;

INSERT INTO provider_runtime_rule_revisions (
    provider_id, revision, schema_version, rules, checksum
)
VALUES (
    'arena',
    1,
    1,
    '{
      "schemaVersion": 1,
      "sessionMaxAgeSeconds": 900,
      "canaryTimeoutSeconds": 60,
      "buildAssetMarkers": ["arena.ai"],
      "discoveryMarkers": {
        "page": ["initialModels"]
      },
      "capabilities": {
        "mediaUploader": "generateUploadUrl,getSignedUrl"
      },
      "endpointPaths": {
        "models": "/text/direct?model_a=max",
        "me": "/api/me",
        "chat": "/nextjs-api/stream/create-evaluation"
      }
    }'::jsonb,
    '4b4d6ce48b473000eb8364508b6c2d5bb8e598886fefc8f1262ad86f5393c6b8'
)
ON CONFLICT (provider_id, revision) DO NOTHING;

INSERT INTO provider_runtime_rule_states (
    provider_id, active_revision, candidate_status
)
VALUES ('arena', 1, 'IDLE')
ON CONFLICT (provider_id) DO NOTHING;

--rollback DELETE FROM provider_runtime_rule_states WHERE provider_id = 'arena';
--rollback DELETE FROM provider_runtime_rule_revisions WHERE provider_id = 'arena' AND revision = 1;
--rollback DELETE FROM providers WHERE id = 'arena';
