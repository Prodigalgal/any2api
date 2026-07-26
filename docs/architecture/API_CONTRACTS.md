# API and Event Contracts

## Public inference

Public endpoints expose OpenAI-compatible behavior:

```text
GET  /v1/models
POST /v1/chat/completions
POST /v1/responses
```

Provider-specific equivalents exist under `/{provider}/v1`. Unified requests route with a `provider/model` identifier.

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

## Java/Python command contract

Long-running automation uses at-least-once Redis Stream delivery. Every command contains:

```text
schema_version
command_id
job_id
idempotency_key
attempt
deadline
operation
provider
scoped_token_reference
payload
```

Python emits accepted, progress, waiting-input, succeeded, failed, and cancelled events. Java acknowledges a terminal result only after the PostgreSQL transaction commits. Redelivery must return the previously committed result for the same idempotency key.

## Internal synchronous APIs

```text
GET  /internal/v1/health
GET  /internal/v1/capabilities
POST /internal/v1/captcha/solve
POST /internal/v1/risk/qwen/headers
POST /api/internal/v1/automation/jobs/{id}/events
POST /api/internal/v1/automation/jobs/{id}/complete
```

Internal APIs require a separate service credential and are never exposed by the public HTTPRoute.

