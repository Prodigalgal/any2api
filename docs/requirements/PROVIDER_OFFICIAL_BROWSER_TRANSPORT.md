# Provider Official Browser Transport

## Goal

Accounts created or reauthorized through browser automation must retain their authenticated browser
identity and use the official page runtime for protected inference requests, so upstream header and
signature changes do not require a downstream release.

## Scope

- Persist cookies, localStorage, IndexedDB, device data, runtime fingerprint and exact Camoufox config.
- Inject the account state and semantic conversation context into the official page main world.
- Let official frontend modules generate application headers, signatures and proof-of-work values.
- Keep account, fingerprint and proxy affinity stable across registration, inference and reauthorization.
- Return changed browser state through the existing encrypted credential-patch contract.
- Preserve Java ownership of API validation, context-length policy, account leasing and event mapping.

## Non-Goals

- An administrator-facing arbitrary Header or JavaScript editor.
- A silent fallback to copied signature algorithms when module discovery fails.
- Browser transport for providers with a stable supported API when it adds no reliability benefit.
- Claiming DeepSeek completion support before an authenticated streaming probe passes.

## Affected Modules

- `automation/lifecycle/browser.py`: versioned browser execution context capture.
- `automation/providers/qwen_risk.py`: page-native Qwen request and response consumption.
- `automation/providers/minmax_browser.py`: official MinMax runtime discovery and transport.
- `backend/provider/minmax`: semantic command, credential patch and account proxy affinity.

## Acceptance

- Registration credentials contain a versioned browser execution context without logging secrets.
- Qwen no longer aborts a page probe and replays it with curl.
- MinMax discovers the official bridge without a CDN filename or numeric module ID.
- MinMax official-browser config smoke returns HTTP 200 in Camoufox.
- Cross-provider storage origins are rejected during MinMax context restoration.
- Python lint/tests, Java tests and frontend lint/build pass.
- Authenticated Qwen and MinMax SSE probes remain mandatory before production rollout.
