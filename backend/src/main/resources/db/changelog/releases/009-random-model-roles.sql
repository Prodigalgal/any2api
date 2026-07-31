--liquibase formatted sql

--changeset any2api:009-001-random-model-roles
ALTER TABLE models ADD COLUMN random_roles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
CREATE INDEX idx_models_random_roles ON models USING GIN(random_roles);

--rollback DROP INDEX IF EXISTS idx_models_random_roles;
--rollback ALTER TABLE models DROP COLUMN IF EXISTS random_roles;
