--liquibase formatted sql

--changeset any2api:034-001-initial-inference-check
CREATE TABLE account_inference_checks (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    claim_token UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'READY', 'FAILED')),
    source VARCHAR(16) NOT NULL CHECK (source IN ('INITIAL', 'HISTORICAL')),
    model_id TEXT NOT NULL DEFAULT '',
    error_class TEXT NOT NULL DEFAULT '',
    output TEXT NOT NULL DEFAULT '' CHECK (char_length(output) <= 8000),
    duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

--rollback DROP TABLE IF EXISTS account_inference_checks;

--changeset any2api:034-002-initial-inference-history
INSERT INTO account_inference_checks (
    account_id, claim_token, status, source, model_id, error_class, completed_at
)
SELECT id, id,
       CASE WHEN metadata->>'inference_probe_status' = 'READY' THEN 'READY' ELSE 'FAILED' END,
       'HISTORICAL', COALESCE(metadata->>'inference_probe_model', ''),
       CASE WHEN metadata->>'inference_probe_status' = 'READY' THEN ''
            ELSE COALESCE(metadata->>'inference_probe_error', 'HistoricalVerificationFailed') END,
       CURRENT_TIMESTAMP
FROM accounts
WHERE metadata->>'inference_probe_at' IS NOT NULL
ON CONFLICT (account_id) DO NOTHING;

--rollback DELETE FROM account_inference_checks WHERE source = 'HISTORICAL';
