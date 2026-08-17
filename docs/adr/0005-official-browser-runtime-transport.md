# ADR 0005: Official Browser Runtime Transport

- Status: Accepted
- Date: 2026-08-17

## Context

Qwen, MinMax and DeepSeek do not derive every protected request field from HTTP semantics alone.
Their official frontend runtime combines account state, browser APIs, current application code,
cookies, device fingerprint and sometimes proof-of-work before sending a request. Reimplementing
those details in Java or replaying browser-generated headers through another HTTP stack creates a
different TLS, HTTP/2, cookie and proxy identity and requires a deployment whenever upstream code
changes.

## Decision

Java remains responsible for OpenAI protocol validation, canonical conversation semantics, account
leases, model policy, provider orchestration and canonical event decoding. For a provider that
declares an official-browser transport capability, Java sends a semantic command and the encrypted
account credential to Python. Python restores the account's browser execution context, injects the
semantic command into the official page, invokes the official frontend request module and returns
the upstream response plus a credential patch.

The browser execution context is versioned and contains:

- Playwright `storage_state`, including IndexedDB when supported;
- the browser backend and selected fingerprint variant;
- a runtime fingerprint snapshot;
- the exact generated Camoufox configuration when Camoufox was used;
- no hand-maintained encryption key, signature salt or dynamic risk header.

Provider implementations locate official modules by behavioral markers and validate their exported
shape at runtime. CDN filenames and numeric Webpack/Rspack module identifiers are diagnostic data,
not configuration contracts. A provider transport must use the same account-scoped proxy affinity
for registration, inference and reauthorization.

## Rollout

- Qwen: the page main world now performs the real request and consumes the response; curl replay is removed.
- MinMax: buffered and streaming inference use the dynamically located official request module.
  Registration persists its proxy affinity and inference reuses it for the account.
- DeepSeek: official HTTP and PoW modules are confirmed by runtime evidence, but completion migration
  remains disabled until an authenticated stream probe validates the raw response contract.
- Providers with stable documented APIs continue using their existing native clients.

## Failure And Recovery

Missing official modules, invalid stored context, proxy-affinity mismatch and browser startup failure
are explicit transport errors. They do not silently fall back to a manually signed request. Updated
browser state is returned as a credential patch and stored by Java using the existing credential
version/fencing checks. Expired authentication continues through the existing reauthorization task.

## Consequences

This removes routine rebuilds for rotating browser-generated headers and signatures, at the cost of
higher browser memory use and a longer cold start. Runtime module discovery and authenticated live
stream probes become release gates for protected providers.
