# API and Event Contracts

## Public inference

Public endpoints expose OpenAI-compatible behavior:

```text
GET  /v1/models
POST /v1/chat/completions
POST /v1/responses
POST /random/v1/chat/completions
POST /random/v1/responses
POST /multimodal-random/v1/chat/completions
POST /multimodal-random/v1/responses
```

Provider-specific equivalents exist under `/{provider}/v1`. Unified requests route with a `provider/model` identifier.

Random endpoints accept an omitted model or `model=random`. `/random/v1` selects only models carrying
the provider-owned `top_text` role. `/multimodal-random/v1` selects only models carrying the
`top_multimodal` role; a provider must implement image input without dropping content before it may
declare that role. Each endpoint selects an enabled, installed provider with at least one eligible
account, then selects one of that provider's role-qualified enabled models. Concrete model IDs are
rejected on these endpoints. Responses expose the selected route through
`X-Any2API-Provider` and `X-Any2API-Model`.

`GET /v1/models` reads the PostgreSQL runtime catalog and namespaces IDs as
`provider/upstream-model`. `GET /{provider}/v1/models` returns the same catalog without the namespace.
Catalog membership and callability are separate: `cataloged` reports discovery, while `available`
requires a usable account and recent successful inference or a fresh successful probe. The runtime
state is `READY`, `DEGRADED`, or `UNAVAILABLE`; an open per-model circuit always forces
`UNAVAILABLE`.
The inference entry guard reads the same cached catalog and returns `503 model_unavailable` before
queue or account acquisition when a model is marked unavailable.

Each model publishes a machine-readable contract and runtime snapshot:

```json
{
  "id": "acme/acme-ultra",
  "cataloged": true,
  "available": true,
  "supported_parameters": {
    "chat_completions": ["model", "messages", "stream"],
    "responses": ["model", "input", "stream"]
  },
  "provider_options": {"thinking_budget": "integer"},
  "max_context_tokens": 131072,
  "max_input_tokens": 114688,
  "max_output_tokens": 16384,
  "token_limits": {
    "max_context_tokens": 100000,
    "max_input_tokens": 90000,
    "max_output_tokens": 10000,
    "discovered": {
      "max_context_tokens": 131072,
      "max_input_tokens": 114688,
      "max_output_tokens": 16384
    },
    "overrides": {
      "max_context_tokens": 100000,
      "max_input_tokens": 90000,
      "max_output_tokens": 10000
    },
    "source": "ADMIN_OVERRIDE",
    "confidence": "HIGH"
  },
  "reasoning": {"supported": true, "levels": ["low", "medium", "high"]},
  "tools": {"supported": true, "types": ["function"], "parallel": true},
  "streaming": true,
  "multimodal": {"input": ["text", "image"], "output": ["text"]},
  "runtime": {
    "status": "READY",
    "available_account_count": 4,
    "quota_limited_account_count": 1,
    "rolling_request_count": 120,
    "rolling_attempt_count": 126,
    "rolling_success_rate": 0.98,
    "p50_ms": 1830,
    "p95_ms": 6200,
    "probe_status": "READY",
    "circuit_state": "CLOSED"
  }
}
```

Unknown official token limits remain JSON `null`; the gateway does not invent limits. Administrators
may configure lower safety ceilings per provider and model. `token_limits` returns the effective
values, discovered values, nullable overrides, source, and confidence. Overrides cannot exceed a
known discovered value. Model metadata and provider protocol declarations are merged at catalog
synchronization. A provider may override `InferenceProvider.modelContract` when the official catalog
exposes a provider-specific capability shape.

Before queue admission or account acquisition, the gateway rejects an explicit output budget above
the effective output limit. Input is conservatively estimated from UTF-8 request bytes; the gateway
also rejects estimated input above the effective input limit and estimated input plus explicit output
budget above the effective context limit. It returns `invalid_request_error` and never silently
truncates a requested limit.

Common fields enter `CanonicalRequest`; native differences enter:

```json
{
  "provider_options": {
    "qwen": {
      "thinking_mode": "Thinking",
      "thinking_budget": 8192,
      "web_search": true
    }
  }
}
```

`CanonicalRequest.rawRequest` is Java-side validation, telemetry, and compatibility data only. It
must not cross the internal Action boundary. Runtime/API channels receive the canonical maps plus
an explicit `controls` allowlist; each provider mapper is solely responsible for converting those
values into its own upstream field names, nesting, defaults, uploads, and event protocol.

The current Runtime mappers make that translation explicit:

| Provider | Canonical messages | Generation | Reasoning/search | Tools | Media |
|---|---|---|---|---|---|
| Arena | flattened role sections; image/PDF blocks upload in the account page first | unsupported fields rejected before Action | `provider_options.arena.web_search=true` maps to native `modality: "search"` | unsupported | same-session page upload; current declared formats are PNG/JPEG/WebP and PDF |
| DeepSeek | flattened `prompt` with role sections | unsupported fields rejected before Action | `thinking_enabled`/`search_enabled` booleans | search tools become `search_enabled`; other tools rejected | text-only |
| GLM | official `chat.history` and completion `messages` | `params.max_tokens`, `temperature`, `top_p` | completion `features.enable_thinking`, `reasoning_effort`, `auto_web_search` | function tools rejected | authenticated image file upload, then official file object |
| LongCat | flattened `content` with role sections | no output-budget field; unsupported limits rejected | `reasonEnabled`, `searchEnabled`, model-specific `agentId` | function definitions become a provider-local prompt contract | same-session `files` from `/appendix-upload` |
| MiMo | flattened `query` with system/tool sections | `modelConfig.temperature`, `topP`; output limit is non-binding | `modelConfig.enableThinking`, `webSearchStatus` | function definitions become a provider-local prompt contract | same-session `multiMedias` |
| MinMax | flattened `content` with role sections | unsupported standard generation fields are rejected | model `variant`, `enable_team`, `worktreeMode` | rejected | Runtime same-session `attachments`; API currently text-only |
| Qwen | native message graph with `fid`, parent/children and `files` | native `temperature`, `top_p`, `max_tokens` | `feature_config.thinking_mode`, `thinking_budget`, `auto_search` | only search tools; function tools rejected | same-session native image upload |

The direct API channel reuses the same semantic command contract but performs the provider's
HTTP/SSE and upload protocol without creating a browser session. It may extract legal non-empty
`Set-Cookie` values into a bounded `credential_patch`; multi-step adapters merge that patch into
their in-memory account snapshot before the next provider request, and the Java boundary persists
it only against the leased credential version. The following is the current
implemented boundary; it is deliberately narrower where the upstream requires a page-owned
signature, challenge token, or uploader:

When a multi-step API action fails before its final completion request, the Automation Action
boundary preserves an upstream 4xx/5xx status through `ApiActionError` and includes only a bounded,
sanitized response summary. The Java provider can therefore keep credential rejection, anti-bot,
rate limiting, and retryable upstream failures distinct; redirects and missing HTTP status remain
transport failures. A direct API completion response still carries its provider status in the
normal Action result, while SSE exposes the status before data or error frames.

| Provider | API model discovery | API text/SSE | API image | API PDF/file | API prerequisite or limitation |
|---|---|---|---|---|---|
| Arena | HTML direct-page catalog | direct mode and search | Unsupported; use Runtime/AUTO | Unsupported; use Runtime/AUTO | accepts only a provider-issued reCAPTCHA v3 token; no token generation or v2 escalation in API |
| DeepSeek | `/api/v0/client/settings` | session + PoW + completion SSE | Unsupported | Unsupported | PoW is solved locally from the provider challenge; account token/device ID required |
| GLM | `/api/models` | `/api/v2/chat/completions` with current signature fields | multipart `/api/v1/files/` for vision models | Unsupported | frontend version/signature key must match the deployed web protocol; `provider_options.glm.captcha_verify_param` only accepts an existing provider-issued ticket and is never generated here |
| LongCat | Not declared | `/api/v1/session-create` + `/api/v1/chat-completion-V2` | `/api/v1/appendix-upload` | `/api/v1/appendix-upload` | authenticated cookie/access token and returned `fileUrl`/`fileKey` are required |
| MiMo | `/open-apis/bot/config` | `/open-apis/bot/chat` | signed upload + parse flow | Unsupported | `xiaomichatbot_ph` and provider-issued object-storage upload data are required |
| MiniMax | `/archon/api/v1/config` | signed session message SSE | Unsupported; use Runtime/AUTO | Unsupported; use Runtime/AUTO | API signing/account binding remains provider-specific |
| Qwen | `/api/v2/models/` | `/api/v2/chats/new` + completion SSE | STS + Aliyun OSS V4 upload | Unsupported | direct mode uses stored token and optional provider-issued Baxia headers; no Runtime risk browser is started |

An explicitly selected API channel fails closed when its prerequisite is absent; it does not
silently instantiate Runtime. `AUTO` may retry inference or official model discovery through
Runtime only after a classified, retryable API failure and only when the provider declares Runtime
as a supported fallback. Lifecycle actions (registration, reauthentication, keepalive, and daily
check-in) remain Runtime-only even for providers that expose API inference.

For GLM, an upstream `anti_bot_rejected` is a verification challenge, not evidence that the
account credential is invalid. The explicit API channel returns that typed failure and does not
schedule credential reauthentication. `AUTO` may switch to the provider's Runtime flow, where
the verification and the signed completion remain in the same provider-owned browser context;
the API channel only forwards an already provider-issued `captcha_verify_param` when the caller
supplies one. Broad scheduled GLM model probes are disabled because they cannot provide that
provider verification context; explicit readiness probes and real requests remain available.

All API providers use the same conservative failure boundary for known provider verification
signals. `anti_bot_rejected` is kept distinct from `credential_rejected`, puts the affected
account/model into a short cooldown, and lets `AUTO` consider the provider's Runtime channel
when declared. A provider-specific mapper must still submit only its observed native path,
headers, body fields, signature inputs, and upload protocol; no generic OpenAI field or caller
supplied raw header is copied to the upstream. A verification ticket, risk header, PoW result,
or signed upload authorization is accepted only when it was issued by that provider. The system
does not generate, solve, or bypass a provider anti-bot challenge in the API channel.

The table describes adapter behavior, not an upstream compatibility promise. A field is only
accepted when the selected provider contract has a deterministic translation or an explicitly
documented emulation; otherwise Java rejects it before account leasing. The shared Action
Dispatcher validates the canonical envelope before resolving either RuntimeChannel or ApiChannel,
and provider code then performs only provider-native mapping. This prevents a new channel or
provider from accidentally forwarding an OpenAI-shaped object to a non-OpenAI upstream.

MinMax request-only options are isolated in its namespace:

```json
{
  "provider_options": {
    "minmax": {
      "variant": "thinking",
      "agent_role": "mavis",
      "enable_team": false,
      "worktree_mode": false
    }
  }
}
```

Contradictory standard and provider-native parameters return `parameter_conflict`. Unknown provider
options return `unknown_provider_option`. Explicit unsupported features return
`unsupported_parameter`. Strict validation errors include `accepted_parameters` for the selected
protocol so clients can correct a request without querying another endpoint.

Non-streaming and streaming responses normalize token counts. `usage_source=UPSTREAM` means the
provider returned a complete non-zero counter set within a generous bound relative to the public
request and emitted output. Missing, partial, zero, or implausibly inflated fields are replaced from
the actual canonical input and emitted output and return `usage_source=ESTIMATED`. Streaming
responses emit an immediate SSE comment containing the request ID, followed by heartbeat comments
until the first provider event and throughout long quiet periods.
Both response families expose `raw_usage` and `normalized_usage`; compatibility token fields use
the normalized values.

All public success and failure responses include `X-Request-Id`. Provider errors use one envelope
with `type`, `code`, `message`, `param`, `retryable`, `provider`, `model`, and `request_id`.
Authentication and request-validation errors use the same fields. `/healthz` is a public liveness
endpoint and `/readyz` is a dependency readiness endpoint; detailed Actuator metrics require an
administrator session.

Every inference plugin publishes a `protocolContract` in
`GET /api/catalog/v1/providers`. The contract is the machine-readable source of truth for:

- typed `provider_options.<provider>` fields;
- translated Chat Completions parameters;
- translated Responses parameters;
- accepted tool types;
- translated `reasoning` subfields.

Fields absent from the resolved provider contract are rejected before account selection. Explicit
provider routes accept only their own provider-options namespace. Random routes may carry one
namespace per candidate provider; each candidate validates only its own namespace.

## Provider protocol matrix

All rows use the same canonical event stream and central Chat/Responses renderer. `Emulated` means
the provider adapter injects a provider-local prompt contract and parses the model output; it does
not mean the field is forwarded natively. A vendor's product-level multimodal marketing claim does
not automatically make every model and every channel callable through this text adapter. `Native`
below means the current adapter preserves the block and has a local contract fixture; live account
completion is a separate release gate.

| Provider | Chat | Responses | Reasoning | Function tools | Image input | File input | Audio input | Video input | Stored Responses |
|---|---|---|---|---|---|---|---|---|---|
| Arena | Native | Native | Unsupported | Unsupported | Native page upload | Native page upload (PDF) | Unsupported | Unsupported | Unsupported |
| Qwen | Native | Native | Native | Unsupported; search tools only | Native upload | Unsupported | Unsupported | Unsupported | Unsupported |
| LongCat | Native | Native | Native | Emulated | Native upload | Native upload | Unsupported | Unsupported | Unsupported |
| MiMo | Native | Native | Native | Emulated | Native upload | Unsupported | Unsupported | Unsupported | Unsupported |
| MinMax | Native | Native | Native | Unsupported | Native upload | Unsupported | Unsupported | Unsupported | Unsupported |
| GLM | Native | Native | Native | Unsupported | Native upload (vision models only) | Unsupported | Unsupported | Unsupported | Unsupported |
| Grok Build | Native | Native | Native | Native | Native block | Native block | Unsupported | Unsupported | Unsupported |
| Grok Web | Native | Native | Native output | Emulated | Separate media ops | Unsupported in chat input | Unsupported in chat input | Separate media ops | Native |
| Grok Console | Native | Native | Native | Native | Native block | Native block | Unsupported | Unsupported | Stateless only |

For `Native upload`, the page session obtains the provider's temporary upload authorization,
uploads through the provider object-storage path, and sends only the resulting provider file object
in the same account/proxy context. For `Native block`, the current xAI Responses-shaped payload
preserves `input_image`/`input_file` and lets the upstream fetch the declared URL or file ID. A
provider marked `Unsupported` fails before account acquisition; the runtime must never flatten or
silently discard that content. Grok Web's separate media API is not a declaration that Gateway
chat accepts arbitrary content blocks.

Qwen, MiMo, MinMax, GLM, and LongCat currently declare image input only for inline base64 data URLs
because their page upload protocols require browser-side bytes. LongCat additionally declares one
inline-base64 document upload per chat, using the authenticated page's `/api/v1/appendix-upload`
route and the returned `fileUrl`/`fileKey` object in the same chat request. LongCat accepts up to
nine images or one document in this adapter; image and document inputs cannot be mixed. GLM exposes
the capability only for models whose authenticated official catalog metadata contains
`capabilities.vision=true`; other GLM models remain text-only. Their Java adapters reject remote
URLs and file IDs before account leasing; this source restriction is part of the provider contract,
not a fallback to text. The runtime schema records all canonical image/audio/video/file block
aliases, while provider capability, model metadata, and source policy decide whether a block can
proceed. Audio and video are not declared as LongCat chat input capabilities; their presence in
other LongCat product flows does not change this contract.

Arena supports both direct API and Runtime inference. The public request may use either the typed
`provider_options.arena.web_search` option or the provider-contract `web_search` parameter; both
are normalized to the same semantic boolean and the page mapper emits Arena's native
`modality: "search"`. Media blocks must be user-message inputs with inline base64 data URLs. The
Camoufox page locates the current Arena-exported `uploadFile` function, obtains Arena's signed
upload URL through the page-owned action, uploads the bytes, and sends only the returned
`experimental_attachments` objects in `create-evaluation`. Runtime supports the current UI
declaration of PNG, JPEG, WebP, and PDF. The direct API channel supports text/search only because
the current signed uploader and reCAPTCHA escalation are page-owned; audio, video, remote URLs,
file IDs, and unsupported document MIME types fail closed before either channel. A successful
model catalog response is not an inference-ready account; Arena still requires a real text probe
after registration.

Grok channels remain code-installed but may be administratively hot-unplugged. Disabling them does
not weaken protocol validation for the enabled providers.

The shared event guard requires schema version 1, matching request IDs, monotonically increasing
sequence numbers, one response start, paired tool-call events, at most one usage snapshot, and one
terminal completed/failed event. A violation becomes `provider_protocol_violation` rather than a
partially rendered success.

## Canonical event contract

The event schema is versioned independently of provider adapters. Initial event families are:

```text
response.started
reasoning.delta
output_text.delta
tool_call.started
tool_call.arguments.delta
tool_call.completed
usage
response.completed
response.failed
```

Events preserve order, request correlation, provider/account trace identifiers, and a monotonically increasing sequence number. Public responses never reveal internal account identifiers.

## Registration administration

```text
GET  /api/admin/v1/registration-jobs
GET  /api/admin/v1/registration-jobs/page
POST /api/admin/v1/registration-jobs
GET  /api/admin/v1/registration-jobs/{jobId}
POST /api/admin/v1/registration-jobs/{jobId}/cancel
GET    /api/admin/v1/registration-schedules/page
POST   /api/admin/v1/registration-schedules
PUT    /api/admin/v1/registration-schedules/{scheduleId}
PATCH  /api/admin/v1/registration-schedules/{scheduleId}/enabled
DELETE /api/admin/v1/registration-schedules/{scheduleId}
POST /api/admin/v1/accounts/{accountId}/reauthenticate
POST /api/admin/v1/account-probes
GET  /api/admin/v1/models/limits
PUT  /api/admin/v1/models/limits
```

Registration job responses contain counters, status, timestamps, error class, and created account IDs. They never contain provider credentials or mailbox/proxy secrets.
Creation also accepts per-identity flow retries, attempt timeout, consecutive failed-round limit,
proxy policy, browser headless mode, and an optional configured mail domain. One Java attempt owns
one mailbox; provider-local browser retries reuse it.

`GET /registration-jobs/page` uses zero-based `page`, bounded `size`, optional `provider` and
optional `status`, ordered by `created_at DESC, id DESC`. The legacy list endpoint remains available
for compatible callers. Registration schedules support one-time and fixed-interval execution. A
schedule stores the normalized registration job command, exposes paged administration, and creates
an ordinary registration job when due; schedules never bypass the existing job policy or readiness
pipeline. The enable endpoint only changes lifecycle state, while `PUT` replaces the editable plan
contract.

`POST /api/admin/v1/account-probes` accepts `accountId` and an enabled provider-owned `modelId`, then
returns readiness, model, bounded upstream text output, duration, completion time, and refreshed
account state. The legacy account-relative probe endpoint remains compatible and selects the provider
default model.

System settings are administered through `GET /api/admin/v1/settings` and typed `PUT` endpoints for
Temp Mail, registration defaults, and `/settings/provider-keepalive`. Settings are AES-GCM encrypted
in PostgreSQL and are injected into new automation attempts without restarting Java or Python.
Provider keepalive policies contain the interval, deterministic jitter window, and remote automation
parameters; reserved credential, metadata, proxy, mail, and affinity fields cannot be overridden.

## Proxy pool administration

```text
GET    /api/admin/v1/proxy-pools
POST   /api/admin/v1/proxy-pools
PUT    /api/admin/v1/proxy-pools/{poolId}
DELETE /api/admin/v1/proxy-pools/{poolId}
```

`POST` and `PUT` accept `SUBSCRIPTION_URL` or `NODE_LIST`, a write-only `source`, and optional
provider bindings. Each binding selects one or more traffic scopes: `REGISTRATION`, `LIFECYCLE`, or
`INFERENCE`. Unselected traffic is direct. Existing clients may send `providerIds`; those bindings
are interpreted as registration-only. New clients send `bindingScopes`, for example
`{"minmax":["REGISTRATION"]}`. An empty update source preserves the current encrypted value. Read
responses expose metadata, node count, and scoped bindings, never the subscription URL or nodes.
Inference providers consume a stable Action contract through either RuntimeChannel or ApiChannel.
Runtime remains the default and the legacy browser-shaped transport is only a compatibility adapter;
removing an Action binding changes the channel capability, not the public API. A provider may expose
different channel support per Action, so provider-level API availability must not be used to infer
media support.

## Java/Python Action contract

Java owns durable state and calls the Python automation service with a stable Action contract:

```text
action
channel
provider path parameter
payload
```

```text
POST /internal/v1/providers/{provider_id}/execute
operation = register | reauthenticate | keepalive | daily_checkin
```

Python has no domain-table access. Java persists a successful registration or credential patch only after the internal call returns. Provider exceptions are reduced to a non-sensitive error class at the service boundary.

## Action Channel contract

Lifecycle compatibility uses the operation endpoint, but it is converted to the same Action
Dispatcher before reaching a provider. New inference calls use the explicit channel endpoint:

```text
POST /internal/v1/providers/{provider_id}/actions/request
POST /internal/v1/providers/{provider_id}/actions/stream

action = model_discovery | chat | provider_query | media_policy | media_callback | raw_request
channel = camoufox_browser_runtime | api
operation = legacy compatibility name, when required
semantic_command
runtime_plan
payload = credential + optional proxy pool/affinity + constrained runtime options
```

The stream endpoint returns newline-delimited channel events: `status`, `data`, `error`,
`credential_patch` and `runtime_canary`. RuntimeChannel owns browser/context and official
frontend/page request details; ApiChannel owns the provider's verified Web API/CLI API request
details. Neither channel accepts arbitrary JavaScript or unrestricted upstream URLs. `runtime_plan`
contains only bounded declarative Runtime revision data and cannot carry credentials outside the
execution payload.

The old `/transport/request` and `/transport/stream` routes remain as compatibility adapters. They
convert `runtime_mode + operation` to the Action contract and are not a second business path.

## Internal synchronous APIs

```text
GET  /internal/v1/health
GET  /internal/v1/capabilities
POST /internal/v1/captcha/solve
POST /internal/v1/providers/qwen/risk-headers
POST /internal/v1/providers/glm/browser-sessions/{session_id}/captcha
POST /internal/v1/providers/{provider_id}/execute
POST /internal/v1/providers/{provider_id}/actions/request
POST /internal/v1/providers/{provider_id}/actions/stream
```

Internal APIs require a separate service credential and are never exposed by the public HTTPRoute.
