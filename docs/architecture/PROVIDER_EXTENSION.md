# Provider Extension Contract

Adding a provider must not change the public controllers, account scheduler, Redis lease logic,
canonical event renderer, or frontend navigation. Every provider uses one stable lowercase
`providerId` across Java, Python, PostgreSQL, Redis keys, metrics, and audit events.

## Routing and model identity

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

Discovery upserts by `(provider_id, upstream_id)`, increments `catalog_version`, and disables a
missing model only after a provider-specific grace window. In-flight requests retain their resolved
upstream ID and are not affected by a concurrent catalog refresh.

## Extension levels

### OpenAI-compatible upstream

Add one isolated Java provider package containing a class derived from `OpenAiBridgeProvider`. The
class declares its ID, display name, adapter version, cold-start models, capability matrix, and any
request transformation. Spring discovers it automatically; no registry, Controller, or router edit is
required. Deployment configuration supplies only the remote base URL, secret API key, and operational
settings. Add the provider to PostgreSQL and run the contract suite.

### Native or divergent upstream

Add one isolated Java package with an `InferenceProvider` implementation. It owns only:

- validation and normalization of `provider_options.<providerId>`;
- conversion from `CanonicalRequest` to the upstream request;
- conversion from upstream frames to ordered `CanonicalEvent` values;
- provider error classification and retry hints;
- model discovery when the upstream exposes it.

The plugin must not select accounts, acquire Redis locks, persist credentials, render OpenAI SSE, or
schedule retries. Those remain shared platform responsibilities.

## Request processing

```text
authenticate
  -> resolve provider and model
  -> validate capability and provider options
  -> select eligible account
  -> acquire fenced Redis lease
  -> prepare provider request
  -> call upstream
  -> emit canonical events
  -> render Chat/Responses stream
  -> persist usage and release lease
```

Standard parameters are represented once in `CanonicalRequest`. Vendor-only fields are accepted only
under `provider_options.<providerId>`. Unknown fields, conflicting standard fields, and unsupported
capabilities fail before upstream I/O.

## Automation and captcha

If registration or reauthentication is needed, add one Python provider strategy with the same
`providerId`. The strategy owns page selectors, challenge detection, proxy affinity, OTP sequence,
browser backend choice, credential extraction, and post-registration verification.

The shared captcha service owns image preprocessing and solver fusion only. It returns candidate
answers, coordinates, and confidence from ddddocr, captcha-recognizer, OpenCV, or ONNX. The provider
strategy decides how to apply an answer, whether the page accepted it, and whether a retry requires a
fresh challenge. Solver workers never persist browser state or provider credentials.

## Required acceptance tests

- Manifest schema and unique provider ID.
- Both route forms and path/model conflict rejection.
- Chat and Responses, stream and non-stream.
- Tool calls, reasoning, multimodal input, usage, cancellation, and error mapping as declared.
- Account lease fencing and client disconnect cleanup.
- Registration idempotency, browser isolation, proxy cleanup, and captcha retry behavior.
- Model discovery add/update/disable behavior.
- A golden fixture for every upstream protocol version.

Only capabilities proven by fixtures and a live smoke test may be declared `NATIVE`.
