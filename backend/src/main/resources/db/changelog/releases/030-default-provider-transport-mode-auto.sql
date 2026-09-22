--liquibase formatted sql

--changeset any2api:030-001-default-provider-transport-mode-auto
UPDATE providers
SET config = jsonb_set(COALESCE(config, '{}'::jsonb), '{inference_transport_mode}', '"AUTO"', TRUE),
    updated_at = CURRENT_TIMESTAMP
WHERE installed = TRUE;

--rollback UPDATE providers SET config = jsonb_set(COALESCE(config, '{}'::jsonb), '{inference_transport_mode}', '"RUNTIME"', TRUE);
