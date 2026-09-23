--liquibase formatted sql

--changeset any2api:031-001-retire-legacy-grok-channels
DELETE FROM models WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM registration_jobs WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM registration_schedules WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM sessions WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM api_key_provider_grants WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM operation_events WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM provider_runtime_rule_states WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM provider_runtime_rule_revisions WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM accounts WHERE provider_id IN ('grok', 'grok_console');
DELETE FROM providers WHERE id IN ('grok', 'grok_console');

--rollback -- no rollback required for retired experimental channels
