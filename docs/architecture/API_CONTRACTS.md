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
Each row reports `catalog_source`, official metadata, and account-backed availability.

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

Contradictory standard and provider-native parameters return `parameter_conflict`. Unknown provider options return `unknown_provider_option`. Explicit unsupported features return `unsupported_parameter`.

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
