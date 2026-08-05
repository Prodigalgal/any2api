# Qwen Durable Authentication Session

## Goal

Make a newly registered Qwen account retain one recoverable browser identity across inference
requests and automation Pod restarts. Normal inference must reuse that identity, persist state
rotated by Qwen or Baxia, and move rejected credentials out of the inference pool until a real
reauthentication plus inference-readiness probe succeeds.

## Scope

- Persist Qwen cookies, allowlisted Alibaba Baxia/WAF cookies, local storage, IndexedDB state,
  browser profile, generated fingerprint, and observed device metadata inside the existing AES-GCM
  encrypted provider credential.
- Generate a different coherent fingerprint per registration, prefer Camoufox, retain Patchright as
  fallback, and restore the selected backend and exact account fingerprint after restart.
- Restore one isolated browser context per account in the Qwen native transport.
- Bind registration, lifecycle, model discovery, media setup, and inference to the account-level
  proxy affinity instead of request-specific egress.
- Return refreshed browser state as a credential patch after normal requests and challenge recovery.
- Disable accounts on explicit credential rejection and enqueue the existing reauthentication flow.
- Require the existing real model probe before an expired account returns to `ACTIVE`.

## Non-Goals

- Reverse engineer or synthesize Baxia `bx-ua` outside the official page runtime.
- Promise that Qwen never presents an internal challenge; challenges must be invisible to public API
  callers when automated recovery succeeds.
- Persist raw browser profile directories, captcha images, response bodies, or credentials outside
  PostgreSQL.
- Change other providers' browser or lifecycle behavior.

## Impacted Modules

- `automation/any2api_automation/providers/qwen.py`
- `automation/any2api_automation/providers/qwen_risk.py`
- `automation/any2api_automation/providers/qwen_session.py`
- `backend/src/main/java/com/any2api/provider/qwen/`
- `backend/src/main/java/com/any2api/provider/ProviderFailureDisposition.java`
- `backend/src/main/java/com/any2api/account/`
- Qwen automation, protocol, inference, and failure-disposition tests

## Acceptance Criteria

1. Registration produces an encrypted credential containing a versioned, origin-restricted Qwen
   browser storage state and backend-specific device fingerprint. Camoufox includes its complete
   generated config and noise seeds; Patchright includes CDP-controlled fields plus validated
   runtime-observed fields. Cursor timing is excluded from the persisted device identity, and a
   legacy manifest migration preserves all device/noise values while disabling nested humanization.
2. Two Qwen accounts never share cookies, local storage, IndexedDB, page state, or challenge state.
3. A fresh automation process can restore the persisted state before the first protected request.
4. Cookies or storage changed by normal requests or captcha recovery are returned to Java and merged
   before the terminal public event is delivered.
5. Invalid state, cross-origin cookies, oversized values, and header injection are rejected at the
   internal API boundary without logging secrets.
6. An explicit `credential_rejected` result marks the account `EXPIRED`, disables it, and coalesces a
   reauthentication action.
7. Reauthentication cannot reactivate an account until the existing account-specific inference
   readiness probe succeeds.
8. Existing Qwen stream/non-stream behavior, protocol mapping, uploads, and other providers do not
   regress.
9. Qwen model discovery, upload setup, chat creation, and completion reuse the same account proxy
   binding and browser fingerprint; a public request ID cannot rotate account egress.

## Verification

- Python Ruff format/check and full pytest suite.
- Java full Gradle test suite.
- Focused tests for browser-state validation, per-account isolation, restart restoration, challenge
  state patches, Java credential propagation, and credential-rejection scheduling.
- Real Qwen registration/model probe plus stream and non-stream API smoke when credentials and
  production access are available.
- Git diff check, secret scan, CI, ArgoCD/Pod/log verification for a production rollout.

## Rollback

Revert the durable-session commits. Existing credentials remain compatible because `browser_state`
and `browser_fingerprint` are additive; legacy credentials continue through the bearer-token and
flat-cookie fallback and acquire a Camoufox fingerprint on their next successful lifecycle/request.
