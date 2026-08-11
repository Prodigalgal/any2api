--liquibase formatted sql

--changeset any2api:018-001-model-token-limit-overrides
ALTER TABLE models
    ADD COLUMN max_context_tokens_override BIGINT,
    ADD COLUMN max_input_tokens_override BIGINT,
    ADD COLUMN max_output_tokens_override BIGINT,
    ADD CONSTRAINT ck_models_context_override_positive
        CHECK (max_context_tokens_override IS NULL OR max_context_tokens_override > 0),
    ADD CONSTRAINT ck_models_input_override_positive
        CHECK (max_input_tokens_override IS NULL OR max_input_tokens_override > 0),
    ADD CONSTRAINT ck_models_output_override_positive
        CHECK (max_output_tokens_override IS NULL OR max_output_tokens_override > 0);

--rollback ALTER TABLE models DROP CONSTRAINT IF EXISTS ck_models_output_override_positive, DROP CONSTRAINT IF EXISTS ck_models_input_override_positive, DROP CONSTRAINT IF EXISTS ck_models_context_override_positive, DROP COLUMN IF EXISTS max_output_tokens_override, DROP COLUMN IF EXISTS max_input_tokens_override, DROP COLUMN IF EXISTS max_context_tokens_override;
