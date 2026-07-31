--liquibase formatted sql

--changeset any2api:010-001-proxy-traffic-scopes
ALTER TABLE provider_proxy_bindings
    ADD COLUMN traffic_scopes TEXT[] NOT NULL DEFAULT ARRAY['REGISTRATION']::TEXT[];

ALTER TABLE provider_proxy_bindings
    ADD CONSTRAINT chk_provider_proxy_binding_scopes
    CHECK (
        cardinality(traffic_scopes) > 0
        AND traffic_scopes <@ ARRAY['REGISTRATION', 'LIFECYCLE', 'INFERENCE']::TEXT[]
    );

CREATE INDEX idx_provider_proxy_bindings_scopes
    ON provider_proxy_bindings USING GIN(traffic_scopes);

--rollback DROP INDEX IF EXISTS idx_provider_proxy_bindings_scopes; ALTER TABLE provider_proxy_bindings DROP CONSTRAINT IF EXISTS chk_provider_proxy_binding_scopes; ALTER TABLE provider_proxy_bindings DROP COLUMN IF EXISTS traffic_scopes;
