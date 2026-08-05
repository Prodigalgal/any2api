# Qwen Slider Risk Analysis

## Objective

Determine with controlled production data whether Qwen's inference slider is correlated with
egress changes, cold browser restoration, incomplete durable state, fingerprint drift, request
headers, or request cadence. The objective is to preserve a legitimate account session inside the
official Qwen page runtime. The implementation does not synthesize Baxia values or bypass an
upstream risk decision outside that runtime.

The measurements in this report were collected on 2026-08-05. Account IDs, IP addresses, tokens,
cookies, mailbox credentials, proxy nodes, and browser storage values were excluded from output.

## Target Flow

```text
POST /api/v2/chats/new
  -> POST /api/v2/chat/completions?chat_id=...
  -> Baxia main-world request headers
  -> optional FAIL_SYS_USER_VALIDATE / punish page
  -> credential patch persisted before terminal delivery
```

The request initiator is the injected main-world fetch in `QwenNativeBrowserTransport`. Baxia's
page-owned runtime adds the protected headers before the request is intercepted and replayed through
the same browser transport session.

## Instrumentation

Each Qwen browser request records only non-secret diagnostics:

- hashed account key and hashed proxy binding;
- created versus reused browser session;
- request sequence and control versus completion request;
- Camoufox/Patchright backend and fingerprint digest;
- durable versus legacy state plus cookie, origin, and IndexedDB counts;
- proxy-bound boolean and challenge outcome.

Raw account IDs, IP addresses, tokens, cookies, storage values, proxy URLs, and proxy credentials are
never logged.

## Cold And Warm Baseline

Four existing accounts were called twice through direct production egress before the header fix.

| Request position | Accounts | Ready | Slider | Mean elapsed |
| --- | ---: | ---: | ---: | ---: |
| First cold restoration | 4 | 4/4 | 4/4 | 47.6 s |
| Immediate second request | 4 | 4/4 | 0/4 | 24.1 s |

The first request was correlated with the challenge in this cohort, but cold restoration alone was
not proven as the cause. One second request also recreated its browser because of catalog eviction
and still completed without a slider.

## Egress Crossover

The same four accounts were moved from direct egress to the existing four-node Qwen proxy pool and
then back to direct egress. Every phase used real chat creation and completion requests.

| Phase | Requests | Ready | Slider | Observed egress |
| --- | ---: | ---: | ---: | --- |
| Direct baseline | 8 | 8/8 | 4/8, all on first request | JP/KIX, hash `2335f420a3b90605` |
| Proxy, two rounds | 8 | 8/8 | 0/8 | JP/KIX for one account; SG/SIN for three accounts |
| Direct restored | 8 | 7/8 | 0/8 | original direct egress |

The proxy phase contained two egress hashes distinct from direct: `6fe01e47c46c0485` and
`24c8d41131efae69`. The one failed request after restoring direct egress was
`empty_model_response`, not a slider or credential rejection. The original production binding was
restored and verified as `qwen | REGISTRATION` after the experiment.

This sample rejects the hypothesis that an IP or country change is by itself sufficient to trigger
the slider: actual JP-to-SG and proxy-to-direct changes produced zero challenges. It does not prove
that IP reputation is irrelevant. ASN reputation, high account density, or a previously punished IP
still require a larger randomized cohort.

## Official Header Audit

The live Qwen web application identified itself as version `0.2.81`. Its request builder adds the
following application-owned headers:

| Layer | Header set | Handling |
| --- | --- | --- |
| Qwen static | `Content-Type`, `Version`, `source`, `X-Request-Id`, `Timezone` | Match the live web builder |
| Qwen completion | `X-Accel-Buffering: no` | Add only to the completion request |
| Baxia dynamic | `bx-ua`, `bx-umidtoken`, `bx-v` | Generate only in the official page main world |
| Browser-owned | `Accept`, `Accept-Encoding`, `Accept-Language`, `Origin`, `Referer`, `Sec-Fetch-*`, `User-Agent` and protocol headers | Let the browser runtime generate them |
| Authentication | Qwen cookies and durable browser storage | Do not add bearer authorization for web/H5 requests |

For `source=web` and `source=h5`, the live Qwen builder explicitly deletes `Authorization`. A
main-world capture compared 22 project header names with 21 official header names. Both sets
contained the browser and Baxia fields; the only project-only field was `authorization`.

The safe minimum is therefore the official emitted set above, not an arbitrary smaller set. A
field-by-field destructive ablation of Baxia or browser-owned headers was not performed because it
would deliberately make valid accounts look anomalous. No evidence supports removing any of those
official fields.

## Header Fix Cohort

The same four accounts were tested twice before and twice after removing the project-added bearer
header.

| Build | First-request slider | Second-request slider | Ready | First-request mean |
| --- | ---: | ---: | ---: | ---: |
| Before fix | 3/4 | 0/4 | 8/8 | 41.3 s |
| After fix | 0/4 | 0/4 | 8/8 | 29.4 s |

All four post-fix first requests created a fresh browser session, so the improvement was not caused
by reusing an already-warm page. On the new production Pod, the following natural traffic window
was also observed for approximately 4.5 hours:

| Operation | HTTP 200 | Slider | Unresolved challenge |
| --- | ---: | ---: | ---: |
| Chat creation | 9 | 0 | 0 |
| Completion | 8 | 0 | 0 |

The source audit plus paired account result makes the extra `Authorization` header the strongest
observed trigger in this incident. The cohort is still small and the comparison was sequential, not
randomized, so this is strong operational evidence rather than a formal causal proof.

## Durable State Findings

- Successful post-fix requests used accounts with 14 and 26 cookies. An exact cookie count is not a
  validity condition; the complete allowlisted state and its values matter.
- Qwen's inspected `sessionStorage` references were temporary UI flags. Persistent device identity
  is in `localStorage`, which is already captured. The observed IndexedDB snippets were capability
  tests rather than missing authentication state.
- Qwen browser state, exact Camoufox config, Firefox preferences, interaction profile, cookies,
  local storage, and observed Patchright identity remain account-scoped and are returned in each
  credential patch.

## GeoIP And Proxy Identity

The existing registration fingerprints forced `Asia/Shanghai` even when a proxy exited elsewhere.
One-off Camoufox generation with `geoip=True` through actual production proxy leases produced:

| Proxy egress | Generated timezone | Generated locale |
| --- | --- | --- |
| JP/KIX | `Asia/Tokyo` | `zh-CN` |
| SG/SIN | `Asia/Singapore` | `zh-CN` |

New Qwen registrations now acquire the proxy first, then generate the Camoufox fingerprint through
that exact lease. The generated timezone and full Camoufox config are persisted with the account.
Patchright remains the fallback backend and receives the same GeoIP-derived timezone, avoiding a
known proxy/timezone contradiction during fallback.

Camoufox `0.5.4` emits `proxy_without_geoip` while replaying an exact config because its warning
checks for a direct `geolocation` key while its own generator stores `geolocation:latitude` and
`geolocation:longitude`. Exact replay now validates timezone plus both coordinate fields locally,
packs the persisted config without asking Camoufox to regenerate GeoIP, and attaches the same proxy
to the final Playwright launch options. This removes the false warning without rotating account
coordinates, timezone, or noise seeds.

Each registration identity also receives a non-secret `proxy_affinity_key`. Rendezvous hashing maps
that key to one pool node, strict affinity prevents silently falling through to a different node,
and the successful key is persisted in the encrypted credential. If `LIFECYCLE` or `INFERENCE` is
later bound to a compatible pool, Java forwards the same key. With no binding for those scopes, the
current direct behavior is unchanged.

### Production Registration Validation

One production registration was executed after deployment with `target=1`, `concurrency=1`,
`REQUIRED_POOL`, Camoufox preferred, and external AI captcha disabled.

| Stage | Result |
| --- | --- |
| Registration attempt | 1/1 succeeded in 75.973 s |
| Registration challenge | One slider; local fused estimate cleared it on attempt 1 |
| Activation | Mail link received, account activated, credential captured |
| Persisted browser | Camoufox `firefox147`, `Asia/Tokyo`, `zh-CN` |
| Persisted state | 18 cookies, one origin, one IndexedDB database, latitude and longitude present |
| Proxy identity | Opaque affinity present, valid `qwen-` prefix, 37 characters |
| Inference readiness | `chats/new` 200 and `chat/completions` 200, no slider or challenge |
| Account admission | `ACTIVE`, enabled, model `qwen3.7-plus`, credential version advanced to 2 |

The registration form challenge and the post-registration inference result are separate outcomes:
the registration slider was solved once, while the newly restored account's real chat request did
not present a slider.

## Response Envelope Limit

The model catalog path returned an automation envelope whose durable `credential_patch` exceeded
Spring WebClient's 256 KiB default, causing `DataBufferLimitException` even though the upstream Qwen
model body itself measured only 4,173 to 23,798 bytes. `QwenRiskHeaderClient` now uses the same
bounded 20 MiB codec limit as `BrowserTransportClient`. A local HTTP fixture returning a 300,000-byte
credential patch verifies this path without removing the upper bound.

## Production Decision

- Keep Qwen `INFERENCE` and `LIFECYCLE` direct for now. The controlled crossover found no slider
  benefit from proxying, and a VLESS lease currently exposes a new ephemeral localhost port per
  transport session. Enabling it would reduce warm-session reuse without supporting evidence.
- Keep the proxy pool bound to `REGISTRATION` only. New accounts receive GeoIP-aligned Camoufox
  fingerprints and durable affinity, so the identity is ready if later data justifies extending the
  scope.
- Preserve official-page Baxia generation and official request headers. Do not restore the extra
  bearer header and do not synthesize Baxia outside the page runtime.

## Residual Risk

- The header cohort contains four accounts and is sequential. A larger randomized holdout is needed
  to quantify confidence and detect account-age interactions.
- IP reputation was tested across three observed egress identities and two countries, not across
  ASNs or punished residential/datacenter ranges.
- Strict registration affinity can fail an attempt when its selected node is busy or unhealthy. The
  durable registration job retries with a new identity; production registration evidence must track
  `NodeBusy` and node-health failures separately from Qwen challenges.
- Reusing a VLESS node does not currently preserve the same local proxy endpoint across separate
  browser transport sessions. Do not enable inference proxying until the transport can retain a
  stable lease or tests show that cold recreation is acceptable.
