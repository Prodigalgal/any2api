--liquibase formatted sql

--changeset any2api:013-001-distribution-api-keys
ALTER TABLE api_keys
    ADD COLUMN expires_at TIMESTAMPTZ;
CREATE INDEX idx_api_keys_enabled_expiry
    ON api_keys(enabled, expires_at);

CREATE TABLE api_key_provider_grants (
    api_key_id UUID NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    provider_id VARCHAR(32) NOT NULL REFERENCES providers(id),
    all_models BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key_id, provider_id)
);
CREATE INDEX idx_api_key_provider_grants_provider
    ON api_key_provider_grants(provider_id, api_key_id);

CREATE TABLE api_key_model_grants (
    api_key_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    model_upstream_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (api_key_id, provider_id, model_upstream_id),
    CONSTRAINT fk_api_key_model_grant_provider
        FOREIGN KEY (api_key_id, provider_id)
        REFERENCES api_key_provider_grants(api_key_id, provider_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_api_key_model_grant_model
        FOREIGN KEY (provider_id, model_upstream_id)
        REFERENCES models(provider_id, upstream_id)
);

CREATE TABLE api_key_protocol_grants (
    api_key_id UUID NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    protocol VARCHAR(32) NOT NULL,
    PRIMARY KEY (api_key_id, protocol),
    CONSTRAINT ck_api_key_protocol_grant
        CHECK (protocol IN ('CHAT_COMPLETIONS', 'RESPONSES', 'IMAGES'))
);

-- Migrate the early JSON scope prototype into constrained relationships.
INSERT INTO api_key_provider_grants(api_key_id, provider_id, all_models)
SELECT key.id, provider.key, jsonb_array_length(provider.value) = 0
FROM api_keys key
CROSS JOIN LATERAL jsonb_each(
    CASE
        WHEN jsonb_typeof(key.scopes -> 'providers') = 'object'
            THEN key.scopes -> 'providers'
        ELSE '{}'::jsonb
    END
) provider
JOIN providers installed ON installed.id = provider.key
ON CONFLICT DO NOTHING;

INSERT INTO api_key_model_grants(api_key_id, provider_id, model_upstream_id)
SELECT key.id, provider.key, model.value
FROM api_keys key
CROSS JOIN LATERAL jsonb_each(
    CASE
        WHEN jsonb_typeof(key.scopes -> 'providers') = 'object'
            THEN key.scopes -> 'providers'
        ELSE '{}'::jsonb
    END
) provider
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE WHEN jsonb_typeof(provider.value) = 'array' THEN provider.value ELSE '[]'::jsonb END
) model
JOIN models catalog
  ON catalog.provider_id = provider.key
 AND catalog.upstream_id = model.value
JOIN api_key_provider_grants provider_grant
  ON provider_grant.api_key_id = key.id
 AND provider_grant.provider_id = provider.key
ON CONFLICT DO NOTHING;

INSERT INTO api_key_protocol_grants(api_key_id, protocol)
SELECT key.id, protocol.value
FROM api_keys key
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE
        WHEN jsonb_typeof(key.scopes -> 'protocols') = 'array'
            THEN key.scopes -> 'protocols'
        ELSE '[]'::jsonb
    END
) protocol
WHERE protocol.value IN ('CHAT_COMPLETIONS', 'RESPONSES', 'IMAGES')
ON CONFLICT DO NOTHING;

UPDATE api_keys SET scopes = '{}'::jsonb WHERE scopes <> '{}'::jsonb;
ALTER TABLE api_keys ALTER COLUMN scopes SET DEFAULT '{}'::jsonb;
COMMENT ON COLUMN api_keys.scopes IS
    'Reserved compatibility column. Authoritative grants are normalized relation tables.';

--rollback DROP TABLE IF EXISTS api_key_protocol_grants, api_key_model_grants, api_key_provider_grants; DROP INDEX IF EXISTS idx_api_keys_enabled_expiry; ALTER TABLE api_keys DROP COLUMN IF EXISTS expires_at;
