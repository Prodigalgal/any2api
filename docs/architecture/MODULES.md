# Modules

## Java modular monolith

| Module | Ownership |
|---|---|
| `api.openai` | Public Chat Completions, Responses, Images, media retrieval, models, and provider-specific paths |
| `api.admin` | Versioned administration API consumed by Next.js |
| `auth` | Admin sessions, CSRF, public API keys, scopes, and rate-limit identity |
| `routing` | Provider/model resolution without provider-specific protocol logic |
| `protocol` | Canonical request, events, Chat renderer, Responses renderer, and errors |
| `provider` | Provider SPI, manifest registry, installation reconciliation, and native adapters |
| `account.command` | Registry-driven provider account commands and optimistic result persistence |
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
| `transport` | Provider-neutral client for bounded opaque browser sessions |
| `media` | Provider media SPI, account orchestration, private asset storage, and model cooldown integration |
| `observability` | Request ID propagation, paged request/operation records, full admin snapshots, telemetry, and metrics |
| `settings` | Encrypted typed runtime settings and provider-neutral registration defaults |

Provider onboarding and model routing rules are defined in
[Provider Extension Contract](PROVIDER_EXTENSION.md).

Concrete Java providers live in separate `provider/<id>/` packages and are discovered as
`InferenceProvider` beans. Concrete Python browser strategies and their settings live in
`providers/<id>.py` and `providers/<id>_*.py` modules and are discovered at process startup. Neither
registry contains a provider switch statement, and architecture tests reject provider IDs in core
modules or sibling-provider references.

## Python automation monolith

| Module | Ownership |
|---|---|
| `lifecycle.browser` | Camoufox/Patchright processes, isolated contexts/profiles, realtime and batch lanes |
| `captcha` | Provider-neutral ddddocr, captcha-recognizer, OpenCV, preprocessing, fusion, and confidence |
| `providers` | Isolated registration, browser reauthentication, and interactive challenges |
| `browser_transport` | Origin-allowlisted opaque curl-cffi sessions, TLS/HTTP2 impersonation, protected request-fingerprint profiles, bounded relative-path HTTP, and session-bound WebSockets |
| `lifecycle.proxy` | sing-box children, node health, flow-affine leases, and cleanup |
| `lifecycle.mail` | Temporary mailbox acquisition, OTP polling, and credential handoff |
| `provider_api` | Provider-neutral execution endpoint and operation dispatch |

Python does not own inference paths, model selection, upstream request bodies, semantic provider
headers, provider signing, quota interpretation, or account state transitions. A provider may
seed cookies or a bearer token and supply a proxy pool when opening an opaque browser session,
while Java selects only a typed transport profile such as `navigation` or `same_origin_fetch`.
Python materializes and protects the concrete browser headers and transport fingerprint, then
sends the Java-owned body to an origin-allowlisted relative path; all protocol decisions remain in
the Java plugin. A provider-local Python helper may mint a browser-bound one-time challenge ticket
against the proxy already leased by an opaque session; it cannot construct or send the inference
request that consumes the ticket.

Opaque session close returns a sanitized credential patch. Providers attach it to their execution
result or context; only the shared account coordinator may merge and encrypt it. Provider packages
never receive repositories or a credential writer.

Provider-specific account mutations implement `ProviderAccountCommandHandler`. Core account and UI
code consumes command descriptors and never names a concrete provider or command. Results merge only
when the account and credential versions still match the execution snapshot.

## Frontend

Next.js owns presentation only. It does not duplicate Java business APIs through custom Next API
routes. Java retains authentication authority. Provider selectors consume the registry-backed
`/api/catalog/v1/providers` endpoint, never model rows or a frontend provider list.

The administration surface uses Spring Security with the signed `HttpOnly`, `Secure`,
`SameSite=Strict` session issued only after the math challenge, proof of work, and administrator
credential checks. Browser HTTP Basic authentication is forbidden because its native challenge
would bypass the designed login experience and expose an unrelated credential prompt. An expired
session returns a plain `401`; Next.js redirects to `/login` and clears cached administration data.
