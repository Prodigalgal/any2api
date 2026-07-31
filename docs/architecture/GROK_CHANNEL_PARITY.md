# Grok Build, Web, and Console Parity Contract

This document defines the required Any2API behavior against the local reference snapshot at
`tmp/chenyme-grok2api`. It is an implementation and acceptance contract, not a claim that the
current code already provides every item.

Anthropic Messages is explicitly outside the Any2API product surface. Parity applies only to
OpenAI Chat Completions, OpenAI Responses, image generation/editing, and video operations.

## Channel ownership

One xAI login may project into three independent provider accounts:

| Channel | Credential | Durable state | Upstream surface |
| --- | --- | --- | --- |
| `grok` | OAuth access/refresh token | independent health, quota, concurrency | Build CLI Responses |
| `grok_web` | SSO plus browser-session context | independent tier, quota, health, egress | grok.com Web |
| `grok_console` | SSO plus browser-session context | independent quota, health, egress | console.x.ai |

The projections share an anonymous `identity_group_id` and egress identity. They never share an
account row, lease counter, health state, quota state, cooldown, or inference eligibility. A Build
probe cannot activate Web or Console.

## Capability matrix

Status meanings: `DONE` is implemented and covered by tests, `PARTIAL` is structurally present but
not live-equivalent, and `MISSING` requires implementation.

| Area | Reference behavior | Any2API status | Acceptance requirement |
| --- | --- | --- | --- |
| Account projection | SSO can project Web to Console and link Build | PARTIAL | Build SSO creates disabled Web/Console rows; refresh preserves independent state and context |
| Chat Completions | Web and Console normalize to channel protocol | PARTIAL | Web Gateway text is live-proven across two configured egress routes; finish tools, usage/cancellation/error fixtures, and Console parity |
| Responses | Web stores conversation state; Console forces stateless | PARTIAL | Web two-turn continuation is live-proven; finish public endpoint CRUD/affinity acceptance and Console stateless parity |
| Models | built-in catalogs; Web filtered by tier | PARTIAL | model availability reflects at least one eligible account, not merely any account |
| Web tools | prompt injection plus streamed tool-call recovery | DONE | nested/flat schemas, forced choice, history, arbitrary chunk boundaries, parallel calls, undeclared-call rejection, and live forced call |
| Console tools | native Responses functions | PARTIAL | OpenAI function tools and tool outputs round-trip without silent field loss |
| Browser transport | Chromium TLS/HTTP2 plus browser request fingerprint | DONE | curl-cffi owns HTTP; a thread-owned Camoufox/Patchright bridge owns Gateway WebSockets that libcurl cannot complete |
| Proxy affinity | stable account identity and provider-scoped egress | DONE | rendezvous-selected strict identity binding is covered by Python and Java fixtures; live proxy-change evidence remains in the release matrix |
| Clearance | browser bootstrap bound to target plus proxy | PARTIAL | exact-proxy Patchright bootstrap, encrypted cookies, matching UA/profile, and session application are fixture-proven; live forced challenge remains |
| Clearance concurrency | cache, singleflight, distributed refresh lock | PARTIAL | one encrypted refresh per binding with bounded wait/jitter is implemented; stale healthy fallback and live multi-replica evidence remain |
| Statsig | metadata normalization, signed cache, optional fallback | PARTIAL | current source-derived indices, dynamic CSS class, and 70-byte validation are live-proven; bounded cache/invalidation telemetry remains |
| Anti-bot recovery | code 7 refreshes Statsig; Cloudflare HTML refreshes Clearance | PARTIAL | first application frame is preflighted; Statsig and distributed clearance each retry once without account penalty; egress feedback remains |
| Definitive account block | distinguished before egress invalidation | DONE | blocked-user and explicit permanent access denial precede code-7 handling; bare permission and safety denial do not block accounts |
| Web quota | fast/auto/expert/heavy and weekly credit windows | DONE | official modes/rate-limits, tier sync, and PostgreSQL per-account/model cooldown are implemented and fixture-covered |
| Console quota | upstream rate state, no invented free allowance | MISSING | real probe and 429 cooldown; no fixed entitlement assumption |
| Account settings | terms, birth date, NSFW configuration | PARTIAL | registry-driven commands and one multi-origin lease are done; terms are live-proven, birth/NSFW remain fixture-proven |
| Attachments | bounded image/file fetch and upstream upload | MISSING | host allowlist, byte limits, MIME validation, same account/egress lease |
| Image lite | chat-backed image generation | PARTIAL | URL/base64, bounded count, early final-image stop, protected download, and fixtures are done; live smoke remains |
| Imagine | WebSocket image generation and partial images | PARTIAL | browser-session WebSocket, 4/8/12 batching, moderation, ordering, final collection, and fixtures are done; partial-image SSE and live smoke remain |
| Image editing | upload fallback, media post, edit stream | PARTIAL | bounded multipart input, V2/legacy upload, media post, edit payload, final asset and fixtures are done; partial-image SSE and live smoke remain |
| Video | optional reference upload, generation, authenticated download | MISSING | 1-15 seconds, progress, trusted asset hosts, same account identity on download |
| Media storage | archive private upstream assets | PARTIAL | PostgreSQL-backed expiring private image assets and gateway-owned URL/base64 responses are implemented; production cleanup/object-storage policy and live smoke remain |
| Egress UI/API | nodes, sources, tests, assignments, clearance refresh | PARTIAL | extend existing pools with health, scopes, account binding, test and refresh actions |

## Required request chain

```text
Java gateway
  -> resolve provider/model and validate OpenAI request
  -> lease provider account in Redis
  -> lease provider egress binding by identity_group_id
  -> obtain browser session context (proxy, UA, CF cookies, fingerprint revision)
  -> Python browser transport performs only the fingerprint-sensitive upstream I/O
  -> Java provider maps upstream frames to CanonicalEvent
  -> Java renders OpenAI output and persists usage/state
  -> release account and egress leases
```

Python remains stateless with respect to domain accounts. It may hold bounded process-local browser
sessions, but Java is the only writer of encrypted credentials, egress bindings, health, quota,
cooldown, lifecycle schedules, and audit data.

## Current Grok Web Gateway

The current Grok Web text path is the browser Gateway, not the legacy
`/rest/app-chat/conversations/new` endpoint:

```text
GET /api/auth/session -> dynamic session.userId
WSS /ws/mgw/?uid=<dynamic user id>
  -> session.create
  <- session.created
  <- conversation.attached
  -> conversation.item.create
  -> response.create
  <- response.created / response.chunk* / response.done
```

`conversation.item.create.parent_response_id` is present for a normal continuation. Regeneration is
a distinct operation and places its parent on `response.create`; the two shapes must not be merged.
Java creates and decodes all Gateway events. Python only opens the real browser WebSocket with the
session's exact proxy and cookies and moves bounded JSON frames across the internal transport API.

Sanitized live acceptance currently covers a complete text response through two independent
configured egress routes, a Gateway handshake plus official mode/quota keepalive, and a two-turn Responses
continuation where the second turn starts in a fresh browser context. The supplied HAR remains an
external diagnostic artifact and is not copied into the repository.

A browser session may declare several globally allowlisted HTTPS origins at creation time. Each
request still uses a relative path and may target only one of those predeclared origins. This keeps
SSO account-management steps on one proxy lease and cookie jar without turning the transport into
an arbitrary URL forwarder.

## Current Grok Web ownership

Java now owns Web chat/continuation paths, request mapping, current-source Statsig parsing/signing,
stream decoding, Responses state/account affinity, keepalive classification, official mode discovery,
quota parsing, tier metadata, provider-local function-tool and media protocols, protected-asset
validation, and account/model failure disposition. Python owns the generic
curl-cffi browser session, TLS/HTTP2 impersonation, browser/request fingerprint profiles, proxy lease,
cookie jar, bounded HTTP and WebSocket frames, and session cleanup. Java may select a typed profile but cannot
override its protected fingerprint headers. The former provider-specific Python transport, Statsig
signer, keepalive, and quota implementation have been removed.

Session-close credential patches follow the inverse path without giving the provider database access:
Python returns only sanitized Cloudflare cookies, matching User-Agent, and browser profile; the Java
execution context captures the patch; the account coordinator merges it under optimistic credential
version control and encrypts the new payload. Concurrent completions retry the merge against the newest
credential version instead of dropping a newly refreshed clearance.

Cloudflare recovery is split by ownership. Python solves the challenge only inside a new Patchright
context carrying the exact session proxy, User-Agent, SSO cookies, and profile, then applies the
allowlisted Cloudflare cookies back to that process-local HTTP session. Java derives a non-secret
binding digest, elects one refresher with a Redis lock, encrypts the resulting context with the
credential master key, and lets waiters apply it to their own sessions. Provider JSON 403 and
`permission-denied` never enter this path; only a recognized Cloudflare HTML marker does.

## Browser-session binding

A reusable context is valid only for the tuple:

```text
(provider scope, identity_group_id, target origin, rendered proxy URL,
 user-agent profile, clearance revision)
```

Changing any tuple member invalidates the context. SSO is never included in logs, cache keys, proxy
labels, or metrics; use a non-reversible keyed digest. Web and Console may share the digest and proxy
identity, but keep separate health and quota state.

The bootstrap worker must return only sanitized Cloudflare cookies (`cf_clearance`, `__cf_bm`,
`_cfuvid`, and `cf_chl_*`), the matching User-Agent, expiry/refresh timestamps, and a profile name.
It must drop SSO and application cookies from solver output.

## Failure and retry contract

| Evidence | Classification | Account action | Egress/session action |
| --- | --- | --- | --- |
| HTTP 401 after upstream application handling | credential rejected | expire and reauthenticate | retain node unless transport also failed |
| Web JSON `error.code=7` | anti-bot rejected | no account penalty | invalidate Statsig and Clearance; retry once |
| Cloudflare HTML/challenge 403 | clearance or egress denied | no account penalty | refresh context; then cooldown node/binding |
| explicit blocked-user/definitive account body | account blocked | degrade/disable after confirmation | do not discard a healthy shared node |
| HTTP 429 | rate limited | model/channel cooldown only | retain credential and egress |
| 5xx/transport failure | upstream/egress transient | no permanent account penalty | health feedback and bounded retry policy |

No ambiguous 403 may permanently ban an account. A retry must preserve request idempotency: Web
conversation creation and media operations retry only before a successful upstream acceptance signal.

## Live acceptance matrix

Every release claiming Grok channel parity must record sanitized evidence for at least three old SSO
accounts and one newly registered identity:

1. Web Fast Chat and Responses, streaming and non-streaming.
2. Console Chat and stateless Responses, streaming and non-streaming.
3. Reasoning and one native/emulated function tool per supported channel.
4. Web tier filtering using Basic and, when available, Super/Heavy accounts.
5. Clearance refresh under the same proxy, then a forced proxy change proving invalidation.
6. Anti-bot code 7 recovery without changing account health.
7. Image lite, Imagine, image edit, video generation, and authenticated media retrieval.
8. 401, Cloudflare 403, definitive block, 429, 5xx, cancellation, and lease-expiry fixtures.

An account becomes `ACTIVE` for a channel only after that channel returns a real completion event.
Page load, SSO presence, model listing, and another channel's successful probe are insufficient.
