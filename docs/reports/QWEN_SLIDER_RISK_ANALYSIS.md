# Qwen Slider Risk Analysis

## Objective

Determine with controlled production data whether Qwen's inference slider is correlated with
egress changes, cold browser restoration, incomplete durable state, fingerprint drift, or request
cadence. The objective is to reduce false-positive challenges by preserving a legitimate account
session, not to synthesize or bypass Qwen's risk decision outside the official page runtime.

## Target Flow

```text
POST /api/v2/chats/new
  -> POST /api/v2/chat/completions?chat_id=...
  -> Baxia main-world request headers
  -> optional FAIL_SYS_USER_VALIDATE / punish page
  -> credential patch persisted before terminal delivery
```

The request initiator is the injected main-world fetch in `QwenNativeBrowserTransport`; Baxia's
page-owned runtime adds the protected headers before the request is intercepted and replayed through
the same browser transport session.

## Baseline Evidence

- Production has 46 enabled `ACTIVE` Qwen accounts.
- The Qwen proxy binding currently covers `REGISTRATION` only. `LIFECYCLE` and `INFERENCE` are
  direct, so registration and later use do not currently share an egress policy.
- The assigned `Self-hosted Oracle` pool is enabled and contains four nodes.
- On automation Pod `any2api-automation-58d757bbb9-fzrxp`, the first measured sample contained five
  chat creations, five completions, five detected sliders, five successful clears, zero unresolved
  challenges, five Camoufox account evictions, and zero terminal Qwen browser-fetch failures.
- A newly registered durable-state account previously completed three consecutive probes without a
  slider. Four older accounts each displayed one slider on their first observed cold restoration.

This baseline is not sufficient to attribute causality to IP. It is selection-biased toward older
accounts and lacks per-challenge egress labels.

## Instrumentation

Each Qwen browser request records only non-secret diagnostics:

- hashed account key and hashed proxy binding;
- created versus reused browser session;
- request sequence and control versus completion request;
- Camoufox/Patchright backend and fingerprint digest;
- durable versus legacy state plus cookie, origin, and IndexedDB counts;
- proxy-bound boolean and challenge outcome.

Raw account IDs, tokens, cookies, storage values, proxy URLs, and proxy credentials are never logged.

## Controlled Experiment

Use the same account cohort in a reversible crossover:

1. Direct egress, two rounds.
2. Existing proxy pool enabled for Qwen inference, two rounds.
3. Restore direct egress, one round.

For every round record requests, sliders, clears, final readiness, elapsed time, credential version,
session lifecycle, state shape, fingerprint hash, and proxy binding hash. IP is considered a primary
factor only if a challenge increase follows an egress transition, falls on the second stable round,
and rises again when switching back. All production binding changes must restore the original scope
in a `finally` path.

## Open Questions

- Whether the successful registration node can be reused after account creation. Registration is
  currently unkeyed, while inference uses account ID affinity and lifecycle has no affinity key.
- Whether the fixed `Asia/Shanghai` and `zh-CN` Camoufox config matches each proxy node's GeoIP.
- Whether Qwen stores risk-relevant state in `sessionStorage`, which Playwright storage state does
  not capture.
- Whether keeping one account page warm materially reduces challenges compared with exact cold
  restoration after another Camoufox account is selected.
