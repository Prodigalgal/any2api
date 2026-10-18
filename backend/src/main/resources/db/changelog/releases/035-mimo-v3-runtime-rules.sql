--liquibase formatted sql

--changeset any2api:035-001-mimo-v3-runtime-rules
UPDATE provider_runtime_rule_revisions
SET rules = '{
  "schemaVersion": 1,
  "sessionMaxAgeSeconds": 900,
  "canaryTimeoutSeconds": 60,
  "buildAssetMarkers": ["xiaomimimo.com"],
  "discoveryMarkers": {
    "requestModule": ["/open-apis/react/chat", "genUploadInfo"]
  },
  "capabilities": {
    "chat": "completions",
    "models": "getConfig"
  },
  "endpointPaths": {
    "chat": "/open-apis/react/chat",
    "models": "/open-apis/bot/config",
    "uploadInfo": "/open-apis/resource/genUploadInfo",
    "parse": "/open-apis/resource/parse"
  }
}'::jsonb,
checksum = 'aafceac68d80581faddd3f35bc2f41615d0e64463cedef224e4ec07cf31e048c'
WHERE provider_id = 'mimo' AND revision = 1;

--rollback UPDATE provider_runtime_rule_revisions SET rules = jsonb_set(rules, '{endpointPaths,chat}', '"/open-apis/bot/chat"'::jsonb) WHERE provider_id = 'mimo' AND revision = 1;

--changeset any2api:035-002-disable-deepseek-provider
UPDATE providers SET enabled = FALSE WHERE id = 'deepseek';
UPDATE registration_schedules SET enabled = FALSE WHERE provider_id = 'deepseek';
UPDATE registration_jobs SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP,
    last_error_class = 'PROVIDER_DISABLED', last_error_detail = 'Provider deepseek registration is disabled'
WHERE provider_id = 'deepseek' AND status IN ('PENDING', 'RUNNING');

--rollback UPDATE providers SET enabled = TRUE WHERE id = 'deepseek'; UPDATE registration_schedules SET enabled = TRUE WHERE provider_id = 'deepseek';
