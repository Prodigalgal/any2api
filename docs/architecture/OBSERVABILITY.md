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
duration, and final outcome. Retries keep the same durable and client-visible `request_id`; the
separate `attempt` column makes `(request_id, attempt)` unique.

Every usage attempt stores `queue_ms`, `account_acquire_ms`, `ttfb_ms`, and `generation_ms`.
`request_kind` distinguishes client inference from a real `PROBE`. The admin timeline therefore
shows account switching without fragmenting one logical request across unrelated identifiers.

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
- `any2api.inference.duration`: `provider`, `model`, `protocol`, `outcome`
- `any2api.inference.stage.duration`: `provider`, `model`, `stage`
- `any2api.model.concurrent`: current admitted requests by provider and model
- `any2api.model.queue.depth`: current bounded waiters by provider and model
- `any2api.model.queue.rejected`: rejected requests by provider, model, and stable reason
- `any2api.model.circuit.state`: closed `0`, half-open `1`, open `2`
- `any2api.model.accounts`: eligible, available, and quota-limited account gauges
- `any2api.model.health`: unavailable `0`, degraded `1`, ready `2`
- `any2api.model.success.rate`: rolling logical-request success ratio

Provider and model labels come only from the bounded enabled catalog. Correlation IDs, account IDs,
API-key IDs, emails, and exception messages are never metric labels. They belong in durable events
or structured logs, because putting them in metrics would create unbounded cardinality.

Rolling model health groups all attempts by `request_id`: the final attempt determines success and
the sum of attempt durations feeds P50/P95. A fresh failed probe newer than the last successful call
forces the model unavailable. Client cancellation releases admission without counting as an
upstream circuit failure.

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

Layered catalog and API-key cache reads use L1 Caffeine, L2 Redis, then L3 PostgreSQL. L3 results
populate L1 synchronously and return immediately; the idempotent L2 write runs asynchronously with
one bounded Reactor retry. Redis read failure falls back to PostgreSQL, while Redis write latency can
never extend a client request.
