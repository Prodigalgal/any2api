# Registration and Challenge Automation

## Registration state machine

```text
queued -> preparing_identity -> acquiring_mailbox -> acquiring_proxy
       -> acquiring_browser -> navigating -> submitting_identity
       -> awaiting_otp -> solving_challenge -> extracting_credentials
       -> activating -> verifying -> persisting_account -> probing_account
       -> succeeded
```

Terminal and pause states are `failed`, `cancelled`, `expired`, and `waiting_input`. Browser restarts never resume a stale captcha; the provider strategy returns to a safe checkpoint and obtains a fresh challenge.

A provider registration result is inference-pending unless it explicitly proves otherwise. A
PENDING account first completes provider keepalive and then a generic, account-specific Chat probe
with the provider manifest's `top_text` model. The probe must return the requested marker and a
normal completion event before the account becomes ACTIVE. Probe evidence is stored as non-secret
metadata; failures remain disabled and reuse the lifecycle queue's bounded backoff.

## Shared browser platform

One Python service and image contains Camoufox, Patchright, Xvfb, provider plugins, solver libraries, sing-box, and temporary-mail clients. It separates work with internal lanes rather than microservices:

- realtime: reserved for latency-sensitive risk headers;
- reauth: account recovery, higher priority than registration;
- registration: bounded batch work;
- captcha: independent CPU/process semaphores.

Each attempt receives an isolated browser process/context and one node from the proxy pool bound to that provider. The node is held for the entire provider flow and cannot be shared by another flow. Pools can be backed by an HTTPS subscription or an operator-managed node list, including VLESS, HTTP(S), and SOCKS5 nodes. PostgreSQL leases the durable registration job; Redis `SET NX` leases the proxy node across Python replicas. Subscription fetches, Redis, temporary mail, Java calls, and local captcha solvers bypass the provider proxy. Only vendor browser/HTTP traffic uses the leased egress.

Proxy bindings are traffic-scoped. The default and migration-safe scope is `REGISTRATION`; account
keepalive/reauthorization (`LIFECYCLE`) and public requests (`INFERENCE`) remain direct unless an
operator explicitly enables those scopes for the provider. Core schedulers request a scope and
never infer policy from a provider identifier.

Public Qwen, LongCat, and MiMo inference opens one origin-allowlisted Python browser-transport
session per leased account request. The same session carries provider cookies or an internally
seeded bearer token, TLS/HTTP2 impersonation, and the optional `INFERENCE` proxy lease across
control and streaming calls. Without an `INFERENCE` binding the identical path is direct. MinMax
uses its provider-specific fingerprint transport with the same scoped policy. One-time
object-storage uploads use the provider-issued signed URL and never receive account cookies.

The lease is passed into provider code, not hidden behind global proxy environment variables. Therefore browser navigation and follow-up vendor HTTP exchanges share the same egress in one attempt: Qwen sign-in after email activation and Grok OAuth token exchange cannot accidentally fall back to the host network. A failed node fails that attempt; only the durable Java retry starts a new flow and leases another node.

MiMo uses its proven Xiaomi HTTP registration protocol rather than a guessed browser form. It fetches and solves the image captcha, encrypts the registration fields, verifies the mailbox ticket, exchanges `passToken` for MiMo service cookies, and validates the result through one provider proxy lease. The RSA public key is discovered from the current official registration assets. The asset contains preview and production keys in a host-dependent conditional, so the parser selects the branch for the configured account host and rejects ambiguous multi-key assets; `ANY2API_AUTOMATION_MIMO_REGISTRATION_PUBLIC_KEY_DER` is only an operator-controlled fallback. MiMo also owns its Xiaomi-compatible password policy instead of inheriting the generic provider password length.
MiMo reauthentication uses the same provider-owned protocol boundary: it first exchanges a usable
`passToken`, then falls back to Xiaomi password login, mailbox identity OTP, and fresh service-cookie
exchange. Existing mailbox message IDs are snapshotted before the ticket is sent so a historical OTP
cannot be reused. The generic browser form remains only a final compatibility fallback.

LongCat waits for H5Guard readiness, observes the risk and email-apply API transitions, and handles slider, ordered-tap, and connect-dots Yoda layouts before accepting the OTP state. Qwen uses its current form names, page-world Baxia headers, and the measured Aliyun slider movement curve. These behaviors live entirely inside their provider modules.

GLM uses separate Alibaba Cloud Captcha profiles for authentication and chat. Authentication uses
the embedded `36qgs6xb` scene; chat uses the popup `didk33e0` scene. A ticket from one scene is never
reused for the other. The provider first executes the SDK's fixed start action, then treats each
challenge as a multi-round interaction until the official success callback returns a one-time
ticket. Slider layouts expose a 300x300 background and an independent alpha foreground strip. Local
ddddocr/OpenCV estimates are accepted only when they agree within 12 pixels; otherwise an optional
vision solver chooses among the measured candidates. The shared solver calls Any2API's own
`/multimodal-random/v1/chat/completions` endpoint with `model=random`; it never names or depends on a
provider. The gateway may select only an enabled top-multimodal model backed by an inference-ready
account, which prevents a registration attempt from recursively depending on its own pending
account. Direct semantic click/drag layouts use the same restricted `ACTIONS=` schema. Model output
is never treated as acceptance.

Grok registration uses one Camoufox context and one provider proxy lease. It loads the live signup page, waits for the current React Castle provider, discovers the current server action, router state, and Turnstile sitekey, and mints a full Castle request token from that page. Email-code requests, OTP verification, and signup are page-context fetches, so cookies, browser fingerprint, TLS, proxy egress, `conversionId`, Castle token, Turnstile token, `Next-Action`, and router state remain in one flow. Castle is minted again immediately before signup. A request with an empty or short Castle token is rejected locally and is never submitted. The Turnstile solver uses the same flow proxy by default.

After SSO extraction, that same browser context opens Grok Web and reads the allowlisted registration-risk fields embedded in the current RSC response. `botFlagSource=0` without a deny policy is `clean`; `policy=deny,event=$registration` is `denied`; other or missing combinations remain `flagged` or `unknown`. A denied or unknown diagnostic never destroys an already obtained SSO. Denied identities skip OAuth and remain disabled; unknown identities may continue authorization but still require a real channel probe. Only source, policy, score, event, and the normalized status are durable. Raw `botFlagDetails`, page HTML, cookies, and challenge artifacts are not persisted.

Grok exposes four separate outcomes. `ACCOUNT_REGISTERED` means xAI accepted the signup and issued SSO. `REGISTRATION_RISK_CLEAN` means the post-signup Web state is clean. `BUILD_AUTHORIZED` additionally requires a Grok Build OAuth access token. `INFERENCE_READY` additionally requires a real upstream probe for the specific Build, Web, or Console account row. An SSO-only result is persisted as `PENDING`, disabled for inference, and scheduled for bounded reauthentication; it is not counted as an inference-ready account. The current device flow uses the complete scope set and live version/surface/referrer metadata. An OAuth `invalid_grant: Access denied` preserves the registered account but keeps it pending.

Grok reauthentication is owned entirely by the Python Grok provider worker. It escalates through `refresh_token`, saved `sso`/`sso-rw` device OAuth, password login with a dynamically discovered Turnstile sitekey, and device OAuth with the newly issued SSO. The SSO exchange uses the provider-specific `curl_cffi` browser fingerprint and installs both cookie names on `.x.ai` and `accounts.x.ai`; a generic Java or Node HTTP client must not emulate this flow. Java only leases and schedules the generic provider operation, merges the returned credential patch, persists credential expiry, and transitions the account state.

HTTP 403 and `permission-denied` are not sufficient to ban an account. Grok classifies them as ambiguous and retries through controlled A/B evidence: same account with another egress, another account on the same egress, and another account from the same email domain. Only corroborated evidence may attribute the failure to account eligibility, email-domain reputation, or proxy IP/ASN. OAuth-token acquisition success followed by inference 403 is treated separately from SSO-to-OAuth eligibility failure.

MinMax is overseas-only. Its lifecycle flow uses `account.minimax.io` and `agent.minimax.io`; a redirect to `minimaxi.com` invalidates the attempt. The OAuth state, device profile, request token, and request-signing profile are discovered from the current official flow rather than copied from the domestic site. Official assets are restricted to provider-configured CDN hosts, currently including both `cdn.hailuo.ai` and the legacy `cdn.hailuoai.com`; signature salts and version codes are still extracted from the live scripts and are never fixed constants.

MinMax's inference request `user_id` is a protocol field and is not an account identity. Registration accepts an account only after the official `/v1/api/user/info` response matches the registration mailbox. The stable `realUserID` (falling back to `userID`) becomes the provider account's external identity, while the request `user_id` remains isolated in the credential for upstream signing.

The six full-lifecycle plugins for GLM, Grok, LongCat, MiMo, MinMax, and Qwen expose the same
operations. Channel-only plugins such as Grok Console may advertise a strict subset:

```text
register -> external_id + email + encrypted credential input
reauthenticate -> merged credential_patch or terminal auth failure
keepalive -> healthy, auth_expired, optional credential_patch
```

## Challenge pipeline

```text
ChallengeRunner applies bounded attempt/refresh policy
  -> provider strategy detects and captures challenge
  -> shared solvers return candidate estimates
  -> provider strategy filters/fuses candidates
  -> provider strategy maps coordinates and emits browser events
  -> provider strategy verifies its network/DOM success signals
```

Local solvers include ddddocr text/slider, captcha-recognizer slider, OpenCV template/dots/icons, and ONNX Runtime. Provider profiles own thresholds and fusion weights. Turnstile remains browser-bound because its token depends on page, IP, UA, action, and short-lived state.

The runner contains no provider identifiers and no DOM selectors. It only enforces the strategy
contract and bounded retry policy. A provider may expose several challenge kinds from one strategy,
as LongCat does for Yoda slider, dots, and ordered tap. Raw images and response observers remain
attempt-local and are discarded when the browser flow ends.

Only attempt metadata is durable by default. Raw challenge artifacts are opt-in, failure-only, secret-sanitized, and TTL-bound.

## Successful completion

Python returns extracted results to Java over the internal authenticated API. Java inserts or updates the provider-owned account, stores the full credential through AES-GCM, appends only the account ID to the registration job result, and schedules the next lifecycle action. Inference-ready accounts are `ACTIVE` and receive a keepalive probe; registered accounts that still need provider authorization are `PENDING`, disabled, and receive a reauthentication action. Passwords, OTPs, mailbox JWTs, cookies, proxy nodes, and tokens are never stored in `registration_jobs`.
Worker-reported readiness is diagnostic only. Every newly registered account is staged as `PENDING`
and disabled; Java must complete a real provider model probe before promoting it to `ACTIVE`.

Registration jobs are leased with PostgreSQL `FOR UPDATE SKIP LOCKED`. A claimed batch respects the job concurrency, persists every successful account immediately, and then updates aggregate attempt/success/failure counts. A cancelled running job finishes its current bounded batch and cannot be claimed again.
