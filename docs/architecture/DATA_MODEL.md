# Data Model

## Aggregate ownership

- Provider aggregate: provider configuration, manifest version, health, and model catalog.
- Account aggregate: identity, operational state, eligibility, credential references, lifecycle state, and version.
- Registration aggregate: campaign, job, attempt, step events, and final account reference.
- Scheduled action aggregate: handler, entity, due time, generation, attempts, lease, and terminal result.
- Session aggregate: tenant, provider, model, account binding, upstream state, context, and expiry.

## Core tables

| Table | Purpose |
|---|---|
| `providers` | Stable provider identities and runtime enablement |
| `models` | Namespaced model catalog and capabilities JSONB |
| `accounts` | Common indexed account state and non-sensitive metadata |
| `account_credentials` | Versioned encrypted secret payloads |
| `api_keys` | Hash, prefix, scopes, quotas, state, and usage counters |
| `sessions` | Sticky account and provider conversation state |
| `responses` | Durable Responses objects and input context |
| `registration_jobs` | Java-owned automation state |
| `scheduled_actions` | Durable due-time scheduler |
| `outbox_events` | Transactional publication to Redis Streams |
| `usage_events` | Idempotent request telemetry |

## State and concurrency

`accounts.version` supplies optimistic domain concurrency. Runtime Redis leases protect active upstream use. A lease is not durable account state and never substitutes for the row version.

`scheduled_actions.generation` invalidates stale messages. Only one active generation of an action family may exist for an entity. Workers reject messages whose generation no longer matches PostgreSQL.

## Secret storage

Credentials use AES-GCM envelope encryption. Each row stores ciphertext, nonce, algorithm, key version, and credential version. Searchable identity fields remain separate. Secret payloads are never returned by ordinary account APIs and never written into JSONB metadata.

