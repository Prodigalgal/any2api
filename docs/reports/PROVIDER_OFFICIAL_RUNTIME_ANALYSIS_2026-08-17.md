# Provider Official Runtime Analysis - 2026-08-17

## Method

The investigation compared a plain page `fetch` with calls made through the current official
Webpack/Rspack runtime. Only public or read-only configuration endpoints were used; no account
mutation or conversation was performed.

## MinMax

- Current observed frontend build: `prod-web-va-0.1.138`.
- A plain page `fetch` returned HTTP 200 but did not contain `yy`, `x-timestamp` or `x-signature`.
- The official runtime module was located by the joint markers `x-signature`,
  `hasSearchParamsPath` and an exported function that returns `fetch(...)`.
- The observed module ID was `97516`, but the implementation does not store or match that ID.
- Calling the located official function for `/archon/api/v1/config` returned HTTP 200, business code
  `0` and three models. Network evidence contained all three generated headers.
- The repository's Camoufox implementation repeated the same read-only request successfully through
  `official_browser_runtime` and produced a browser-context credential patch.
- A separate generic Camoufox launcher smoke captured schema version `1`, a live user agent and 41
  persisted fingerprint configuration keys without making an external request.
- Registration now persists a deterministic per-identity proxy affinity; inference and
  reauthorization reuse that value instead of selecting an egress per request.

## DeepSeek

- Current observed client version: `2.3.0`.
- A plain page `fetch` omitted the official `x-client-*` headers.
- The official app object exposes an HTTP client that generated the current client headers for a
  read-only settings request.
- The runtime also exposes the official WebWorker solver and `X-DS-PoW-Response` encoder.
- The authenticated raw completion stream has not been validated, so DeepSeek inference remains on
  its existing transport in this release.

## Qwen

The existing implementation already created account-isolated Camoufox/Patchright contexts and
captured Baxia in the page main world, but aborted that request and replayed it through curl. The
replay changed the HTTP client, connection and cookie-jar boundary. The new implementation performs
and consumes the request in the page itself. Authenticated SSE and challenge incidence still require
production-account A/B evidence.

## Stable Reproduction Status

- MinMax official module discovery and buffered read-only request: reproduced in Chrome and Camoufox.
- Qwen page-native transport: covered by unit and integration-contract tests; authenticated live SSE pending.
- DeepSeek official settings client and PoW module discovery: reproduced; authenticated live SSE pending.
