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
| `providers` | Stable identities, code installation state, and administrator enablement |
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
| `provider_response_states` | Provider-local Responses continuation state and account affinity |
| `media_assets` | Short-lived private generated media copied from protected upstream URLs |
| `account_model_cooldowns` | Per-account, per-provider, per-model cooldown without degrading unrelated models |

## State and concurrency

`accounts.version` supplies optimistic domain concurrency. Runtime Redis leases protect active upstream use. A lease is not durable account state and never substitutes for the row version.

`scheduled_actions.generation` invalidates stale messages. Only one active generation of an action family may exist for an entity. Workers reject messages whose generation no longer matches PostgreSQL.

`providers.installed` is reconciled from the provider registries at Java startup. `providers.enabled`
is an administrator decision and is never overwritten by plugin discovery. Effective availability
requires both values. Plugin removal preserves durable provider data but retires pending lifecycle
and registration work so an absent implementation cannot produce retry storms.

An upstream `429` writes `account_model_cooldowns` and does not change the account row. Successful
use clears only the matching model cooldown. Ambiguous anti-bot, Cloudflare, transport, and upstream
5xx failures do not mutate account health; only credential rejection or explicit blocked-account
evidence may apply an account-wide cooldown.

`media_assets` never stores an upstream authenticated URL. The provider downloads the bytes while
its account and proxy lease are still active, validates the media type and size, and then stores a
short-lived private copy. Expired rows are not readable even before physical cleanup. Multi-replica
cleanup uses a PostgreSQL transaction advisory lock and bounded batches for both media and model
cooldowns, so deployment scale-up cannot create a synchronized expiry scan storm.

## Secret storage

Credentials use AES-GCM envelope encryption. Each row stores ciphertext, nonce, algorithm, key version, and credential version. Searchable identity fields remain separate. Secret payloads are never returned by ordinary account APIs and never written into JSONB metadata.
