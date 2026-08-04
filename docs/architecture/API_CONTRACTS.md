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

Unknown official token limits remain JSON `null`; the gateway does not invent limits. The
`token_limits` object also returns the catalog source and `HIGH` or `BEST_EFFORT` confidence. Model
metadata and provider protocol declarations are merged at catalog synchronization. A provider may
override `InferenceProvider.modelContract` when the official catalog exposes a provider-specific
capability shape.

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
not mean the field is forwarded natively.

| Provider | Chat | Responses | Reasoning | Function tools | Image input | Stored Responses |
|---|---|---|---|---|---|---|
| Qwen | Native | Native | Native | Unsupported; search tools only | Native | Unsupported |
| LongCat | Native | Native | Native | Emulated | Unsupported | Unsupported |
| MiMo | Native | Native | Native | Emulated | Native | Unsupported |
| MinMax | Native | Native | Native | Unsupported | Native | Unsupported |
| GLM | Native | Native | Native | Unsupported | Unsupported | Unsupported |
| Grok Build | Native | Native | Native | Native | Unsupported | Unsupported |
| Grok Web | Native | Native | Native output | Emulated | Separate media API | Native |
| Grok Console | Native | Native | Native | Native | Unsupported | Stateless only |

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
POST /api/admin/v1/registration-jobs
GET  /api/admin/v1/registration-jobs/{jobId}
POST /api/admin/v1/registration-jobs/{jobId}/cancel
POST /api/admin/v1/accounts/{accountId}/reauthenticate
```

Registration job responses contain counters, status, timestamps, error class, and created account IDs. They never contain provider credentials or mailbox/proxy secrets.
Creation also accepts per-identity flow retries, attempt timeout, consecutive failed-round limit,
proxy policy, browser headless mode, and an optional configured mail domain. One Java attempt owns
one mailbox; provider-local browser retries reuse it.

System settings are administered through `GET /api/admin/v1/settings` and typed `PUT` endpoints for
Temp Mail and registration defaults. Settings are AES-GCM encrypted in PostgreSQL and are injected
into new automation attempts without restarting Java or Python.

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
Qwen, LongCat, MiMo, and MinMax consume `INFERENCE` through their isolated provider transport;
removing that scope switches subsequent public requests back to direct egress without a restart.

## Java/Python operation contract

Java owns durable state and calls the single Python automation service with:

```text
operation
provider path parameter
payload
```

```text
POST /internal/v1/providers/{provider_id}/execute
operation = register | reauthenticate | keepalive
```

Python has no domain-table access. Java persists a successful registration or credential patch only after the internal call returns. Provider exceptions are reduced to a non-sensitive error class at the service boundary.

## Internal synchronous APIs

```text
GET  /internal/v1/health
GET  /internal/v1/capabilities
POST /internal/v1/captcha/solve
POST /internal/v1/providers/qwen/risk-headers
POST /internal/v1/providers/glm/browser-sessions/{session_id}/captcha
POST /internal/v1/providers/{provider_id}/execute
```

Internal APIs require a separate service credential and are never exposed by the public HTTPRoute.
