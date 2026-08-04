# Provider Extension Contract

Adding a provider must not change the public controllers, account scheduler, Redis lease logic,
canonical event renderer, or frontend navigation. Every provider uses one stable lowercase
`providerId` across Java, Python, PostgreSQL, Redis keys, metrics, and audit events.

## Routing and model identity

The text inference surface is intentionally limited to OpenAI Chat Completions and Responses.
Anthropic Messages routes, request fields, response events, and provider capability declarations are
forbidden. Image generation, image editing, and video use separate media operations; they do not add
another text protocol family. A provider plugin may translate only the two supported text protocol
families and declared media operations; it must not add a third text protocol controller or
protocol-specific branch to shared code. The backend
`OpenAiProtocolSurfaceGuardTest` enforces this boundary.

The public API supports both forms:

```text
POST /v1/responses                 model = acme/acme-ultra
POST /acme/v1/responses            model = acme-ultra
POST /v1/chat/completions          model = acme/acme-ultra
POST /acme/v1/chat/completions     model = acme-ultra
```

The path and model namespace must agree when both are present. A conflict is rejected before an
account is leased. The globally stable model ID is always `providerId/upstreamModelId`; aliases and
display names never become routing keys.

Model metadata has two layers:

1. `default-models` in provider configuration is the cold-start fallback.
2. The PostgreSQL `models` table is the runtime catalog populated by discovery or an administrator.

The provider-level protocol contract and each `DiscoveredModel.metadata` snapshot produce a
`ModelCapabilityContract`. The default implementation publishes supported Chat/Responses
parameters, typed provider options, official token limits, reasoning levels, tools, streaming, and
multimodal input/output. Override `InferenceProvider.modelContract` only when a provider exposes
model-specific capabilities that cannot be represented by the default merge. Core catalog and
controller code must remain provider-neutral.

Random routing is a separate provider-owned declaration. `randomModelPreferences` lists ordered
model IDs for `TOP_TEXT` and, only after native image-input verification, `TOP_MULTIMODAL`. Catalog
synchronization chooses the first available preference for each role and stores typed role tags in
PostgreSQL. Shared routing filters by role and never ranks model names or branches on provider IDs.

Discovery upserts by `(provider_id, upstream_id)` and increments `catalog_version`. A failed or empty
discovery response preserves the previous snapshot. A successful non-empty official snapshot disables
official entries absent from that snapshot in the same transaction. In-flight requests retain their
resolved upstream ID and are not affected by a concurrent catalog refresh.

## Native provider package

Every provider is a native adapter. Add one isolated Java package with an `InferenceProvider`
implementation. It owns only:

- validation and normalization of `provider_options.<providerId>`;
- conversion from `CanonicalRequest` to the upstream request;
- conversion from upstream frames to ordered `CanonicalEvent` values;
- provider error classification and retry hints;
- model discovery when the upstream exposes it.

The manifest declares `MODEL_DISCOVERY` only when the provider implements an authenticated official
catalog call. The shared refresher selects and leases a provider-scoped account only for providers
that declare this capability; core code never switches on provider names.

## Request profiles

Update-sensitive upstream values must not be embedded in request methods. Each provider keeps a typed
request profile for client versions, special header values, app identifiers, signature salts, user
agent values, and device parameters. Values support configuration override. When they are published
only in official web assets, the provider may implement a bounded scheduled refresher with these rules:

- fetch only public assets from explicit HTTPS host allowlists;
- never forward account cookies, tokens, or device identifiers to an asset host;
- parse into an immutable snapshot and swap the complete snapshot atomically;
- retain the last verified snapshot on timeout, parse failure, or empty discovery;
- log refresh status without logging salts, tokens, signed URLs, cookies, or request bodies;
- cover every recognized upstream profile version with a sanitized golden fixture.

Account-specific device identifiers remain in the provider account credential. They are never shared
between providers or synthesized as one process-wide constant.

The plugin must not select accounts, acquire Redis locks, persist credentials, render OpenAI SSE, or
schedule retries. Those remain shared platform responsibilities.

Media support is an independent optional plugin surface. A provider adds a `ProviderMediaHandler`
beside its `InferenceProvider`, declares the relevant image/video capabilities, and owns only its
model validation, upstream payloads, stream or WebSocket frames, and trusted asset origins. The
shared `ProviderMediaRegistry`, `MediaCoordinator`, Images controller, account lease, model cooldown,
and private asset store do not name providers. Unsupported media fields must be rejected by the
handler before upstream I/O rather than discarded.

Fingerprint-sensitive WebSockets follow the same boundary as HTTP. Java supplies a relative path
and provider protocol messages; Python opens the WebSocket only against an origin declared when the
opaque browser session was created. It owns cookies, TLS, proxy, User-Agent, and WebSocket handshake
fingerprint. The provider must download any authenticated media asset in that same session before
closing it, and public responses may expose only Any2API-owned asset URLs or base64 data.

## Request processing

```text
authenticate
  -> resolve provider and model
  -> validate capability and provider options
  -> enter per-model queue/concurrency/circuit guard
  -> select eligible account
  -> acquire fenced Redis lease
  -> prepare provider request
  -> call upstream
  -> emit canonical events
  -> render Chat/Responses stream
  -> persist usage and release lease
```

The shared coordinator performs finite account switching only for normalized failure classes in
the provider retry policy. The standard policy retries `empty_model_response`, rejected or blocked
credentials, transient rate limits, and exhausted per-account quota. Providers may change the
attempt limit or classification, but never implement their own account-selection loop. A streaming
attempt may switch accounts only before its first externally visible provider event; non-streaming
attempts are buffered and may switch before the collected response is committed.

Standard parameters are represented once in `CanonicalRequest`. Vendor-only fields are accepted only
under `provider_options.<providerId>`. A provider may also recognize documented legacy top-level
aliases for compatibility, but its typed option is authoritative. Unknown provider options,
conflicting standard fields, and unsupported capabilities fail before upstream I/O.

Each `InferenceProvider` must return a `ProviderProtocolContract`. Parameter support belongs in this
declaration, not in controller branches or ad hoc provider whitelist calls. The contract separately
declares Chat parameters, Responses parameters, tool types, and typed provider options. Provider
`validate` methods are limited to value ranges, mutually dependent options, model restrictions, and
other semantics that cannot be represented by the structural contract.

Every manifest must declare both `CHAT_COMPLETIONS` and `RESPONSES`; the registry refuses to boot
otherwise. Both public protocol families use the same canonical event stream, including reasoning,
tool calls, usage, completion, and failure. Provider-specific upstream event names never reach the
renderer.

## Account identity

Accounts are provider-scoped. The durable identity is `(provider_id, external_id)` and the runtime
identity is the account UUID. Email is descriptive data only: it is not unique and is never used to
look up, merge, lease, renew, or select an account. Two providers may use the same upstream mailbox
while retaining independent account rows, encrypted credentials, health, quotas, cooldowns, expiry
schedules, and audit history.

Credential encryption binds provider ID, account UUID, credential type, and credential version as
AES-GCM additional authenticated data. Supplying an account to a different provider therefore fails
both the explicit ownership check and authenticated decryption.

## Automation and captcha

If registration or reauthentication is needed, add one Python provider strategy with the same
`providerId`. The strategy owns page selectors, challenge detection, proxy affinity, OTP sequence,
browser backend choice, credential extraction, and post-registration verification.

Provider discovery scans the automation provider package. A provider may also expose internal
FastAPI routers through `AutomationProvider.routers()`; the application mounts the aggregate from
the registry. Adding a provider-specific helper endpoint must therefore not add an import or route
statement to `main.py`.

The shared captcha service owns image preprocessing and solver fusion only. It returns candidate
answers, coordinates, and confidence from ddddocr, captcha-recognizer, OpenCV, or ONNX. The provider
strategy decides how to apply an answer, whether the page accepted it, and whether a retry requires a
fresh challenge. Solver workers never persist browser state or provider credentials.

Provider settings live beside the provider implementation and extend
`AutomationProviderSettings`. Shared `config.py` contains only platform settings. A shared captcha
primitive accepts generic timing, retry, image, and gesture inputs; it must not read provider-local
settings or decide provider acceptance.

Challenge execution has two explicit layers:

- `captcha/strategy.py` defines `ChallengeStrategy`, typed detection/attempt results, retry policy,
  and the provider-neutral `ChallengeRunner`.
- Each provider challenge module implements detection, image acquisition, candidate filtering,
  coordinate mapping, browser events, acceptance signals, and refresh rules.

A shared challenge type name does not imply a shared browser strategy. Qwen FeiLin slider uses raw
network image pairing, a measured nonlinear piece/drag curve, closed-loop correction, and signup
response observation. LongCat Yoda slider uses a clean background, visible-piece offset, near 1:1
piece motion, grab verification, and a one-refresh budget; its dots and ordered-tap layouts use
separate canvas actions. These strategies may reuse the same local CV backends but must not reuse
selectors, coordinate transforms, gesture physics, or success predicates.

## Required acceptance tests

- Manifest schema and unique provider ID.
- Both route forms and path/model conflict rejection.
- Chat and Responses, stream and non-stream.
- Tool calls, reasoning, multimodal input, usage, cancellation, and error mapping as declared.
- Account lease fencing and client disconnect cleanup.
- Registration idempotency, browser isolation, proxy cleanup, and captcha retry behavior.
- Model discovery add/update/disable behavior.
- A golden fixture for every upstream protocol version.
- Media field rejection, input byte/MIME bounds, authenticated asset archival, and arbitrary frame
  boundaries for every declared media capability.

Only capabilities proven by fixtures and a live smoke test may be declared `NATIVE`.

## Add and remove workflow

A new provider is delivered as isolated code rather than a branch in shared code:

```text
backend/.../provider/acme/
  AcmeProvider.java
  AcmeRequestProfile.java
  AcmeRequestMapper.java
  AcmeEventDecoder.java
  AcmeFailureClassifier.java

automation/.../providers/
  acme.py
  acme_settings.py
  acme_challenge.py          # only when needed
```

The Java provider is a Spring bean implementing `InferenceProvider`; the Python provider is a
discoverable `AutomationProvider` subclass. Each runtime manifest is authoritative for the
capabilities it executes: Java owns inference protocols and model discovery, while Python owns
lifecycle operations and browser requirements. Shared controllers and frontend selectors consume
`/api/catalog/v1/providers`; they never infer the set of providers from models and never contain
provider ID branches.

Java periodically reads Python's authenticated `/internal/v1/capabilities` manifest and atomically
caches the last valid lifecycle operation set. The public provider directory merges that set with
the Java inference manifest. Registration creation fails closed until Python explicitly advertises
`register`; a malformed or unavailable refresh never replaces the last valid snapshot.

An administrator can hot-unplug an installed provider through `PATCH
/api/admin/v1/providers/{providerId}` without restarting either backend replica. The shared
PostgreSQL `providers.enabled` flag is checked before every new route and is also used by model and
random catalogs. Disabling a provider atomically hides its models, disables its accounts,
supersedes pending lifecycle actions, and cancels unfinished registration jobs. In-flight requests
may finish, but no new request can enter the provider after the transaction commits. Enabling the
provider restores its routes and capabilities while accounts remain quarantined until they pass a
fresh probe. Credentials, account history, proxy bindings, and audit data are retained.

Deleting both provider implementations is a separate code-level removal. Startup reconciliation
then sets the durable provider row to `installed = false` and applies the same quarantine rules.

Provider isolation tests scan core source for discovered provider IDs. Adding a provider-specific
constant, selector, URL, header, or condition outside its provider package must fail that test.
