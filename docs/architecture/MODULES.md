# Modules

## Java modular monolith

| Module | Ownership |
|---|---|
| `api.openai` | Public Chat Completions, Responses, models, and provider-specific paths |
| `api.admin` | Versioned administration API consumed by Next.js |
| `auth` | Admin sessions, CSRF, public API keys, scopes, and rate-limit identity |
| `routing` | Provider/model resolution without provider-specific protocol logic |
| `protocol` | Canonical request, events, Chat renderer, Responses renderer, and errors |
| `provider` | Provider SDK, manifest registry, remote bridge, and native adapters |
| `account` | Account aggregate, eligibility, selection metadata, and pool views |
| `credential` | Encrypted provider credentials and credential-version invalidation |
| `session` | Tenant-isolated provider stickiness and upstream conversation state |
| `registration` | Durable campaigns, attempts, steps, callbacks, and account insertion |
| `lifecycle` | Probe, refresh, reauth, keepalive, recovery funnel, and state transitions |
| `scheduling` | Durable actions, jitter, backoff, catch-up, budgets, and circuit breakers |
| `usage` | Request events, token accounting, aggregation, and quota input |
| `audit` | Secret-silent operator and system actions |
| `persistence` | Spring Data JPA, JdbcClient, Liquibase, projections, and converters |
| `coordination` | Redis leases, locks, token buckets, Streams, and cache invalidation |
| `automation` | Typed client and event consumer for the Python service |

Provider onboarding and model routing rules are defined in
[Provider Extension Contract](PROVIDER_EXTENSION.md).

Concrete Java providers live in separate `provider/<id>/` packages and are discovered as
`InferenceProvider` beans. Concrete Python browser strategies live in separate `providers/<id>.py`
modules and are discovered at process startup. Neither registry contains a provider switch statement.

## Python automation monolith

| Module | Ownership |
|---|---|
| `browser` | Camoufox/Patchright processes, isolated contexts/profiles, realtime and batch lanes |
| `captcha` | ddddocr, captcha-recognizer, OpenCV, preprocessing, fusion, and confidence |
| `registration` | Provider page strategies and credential extraction |
| `risk` | Realtime provider browser state such as Qwen Baxia headers |
| `proxy` | sing-box children, node health, exclusive leases, and cleanup |
| `mail` | Temporary mailbox acquisition, OTP polling, and credential handoff |
| `events` | Redis Stream commands, progress, cancellation, and Java callbacks |

## Frontend

Next.js owns presentation only. It does not duplicate Java business APIs through custom Next API routes. Java retains authentication authority and exposes a versioned OpenAPI contract used to generate the TypeScript client.
