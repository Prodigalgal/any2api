# Full-Chain Observability

## Correlation contract

One identifier follows each operation across its owning boundary:

| Flow | Correlation source | Durable record |
|---|---|---|
| Chat, Responses, random, and Images | Canonical request ID | `usage_events.request_id` |
| Registration attempt | Java scheduler | `operation_events.correlation_id` |
| Keepalive, reauthentication, and readiness probe | Java scheduler | `operation_events.correlation_id` |

Public inference responses include `X-Any2API-Request-Id`. Java stores the same value with the
distribution key, selected provider, selected account, model, protocol, retry attempt, token usage,
duration, and final outcome. A retry appends its attempt number to the durable request key without
changing the client-visible correlation ID.

Java propagates the correlation ID through Reactor Context only to WebClients whose destination is
the internal Python service. Python returns the correlation header and records the final response
body or streaming termination. Internal correlation headers are never forwarded to provider
origins.

## Durable operation events

`operation_events` is an append-per-attempt timeline. The scheduler inserts `RUNNING` before remote
automation begins and changes that row to `SUCCEEDED`, `FAILED`, or `CANCELLED` at the terminal
stage. It does not store browser pages, screenshots, credentials, mail content, captcha images, or
provider response bodies.

Registration jobs additionally retain the latest structured error code, stage, sanitized detail,
and correlation ID. The admin UI exposes the complete attempt timeline. Account rows expose the
same view for lifecycle operations, so operators do not need database or pod access for routine
triage.

## Structured error boundary

Python provider workers return this internal-only error envelope:

```json
{
  "detail": {
    "error": {
      "code": "challenge_failed",
      "stage": "captcha",
      "message": "sanitized diagnostic",
      "error_type": "SolverRejected",
      "retryable": true,
      "correlation_id": "operation correlation"
    }
  }
}
```

Java persists the stable `code` and `stage`; exception class names remain secondary diagnostics.
Provider packages own their stage details, while the shared boundary owns envelope translation and
redaction. Unknown failures become `provider_operation_failed` at stage `provider_operation`.

## Metrics and labels

Spring Actuator exposes these Micrometer timers through the configured metrics endpoint:

- `any2api.operation.duration`: `domain`, `provider`, `operation`, `outcome`
- `any2api.inference.duration`: `provider`, `protocol`, `outcome`

Correlation IDs, account IDs, API-key IDs, model strings, emails, and exception messages are not
metric labels. They belong in durable events or structured logs, because putting them in metrics
would create unbounded cardinality.

## Redaction rules

The Java persistence boundary and Python API boundary both remove:

- password, token, authorization, cookie, JWT, and secret field values;
- email addresses;
- URL query strings;
- embedded base64 data URLs;
- details beyond 1,200 characters.

Provider code may add a stable error code and non-sensitive stage, but cannot bypass the shared
sanitizer. Raw browser or provider payloads remain ephemeral and must not enter logs or PostgreSQL.

Terminal operation events are retained for 30 days and inference usage events for 90 days by
default. A single advisory-lock owner deletes bounded batches. `RUNNING` events are never deleted;
durable scheduler lease recovery first changes abandoned attempts to `FAILED/lease_expired`.

## Failure behavior

Inference telemetry writes run on the shared virtual-thread database executor after the response
signal, so PostgreSQL latency cannot block the provider response event loop. Registration and
lifecycle events remain synchronous with their durable scheduler transitions because losing those
events would make recovery state ambiguous.
