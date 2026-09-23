--liquibase formatted sql

--changeset any2api:031-001-retire-legacy-grok-channels
DELETE FROM provider_runtime_rule_states WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM provider_runtime_rule_revisions WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM accounts WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM providers WHERE id IN ('grok', 'grok_console');

--rollback -- no rollback required for retired experimental channels
