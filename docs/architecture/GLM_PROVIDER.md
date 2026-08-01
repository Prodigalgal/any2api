# GLM Provider

## Ownership

The GLM provider is split by capability, not by deployment:

- Java owns model discovery, Chat/Responses mapping, request fingerprints, double-HMAC signing,
  upstream SSE decoding, account selection, leases, retries, and state transitions.
- Python owns registration, reauthentication, Alibaba Cloud Captcha browser execution, temporary
  mail, provider proxy leases, and browser-bound completion transport.
- The provider-local runtime returns an opaque one-shot flow ID after the official captcha callback.
  Java then maps and signs a fresh completion request. Python injects the retained ticket and sends
  that request from the same Playwright page that solved the challenge; it does not map or sign the
  provider protocol.

No GLM identifier, URL, selector, header, or protocol branch exists in shared routing code.

## Official request flow

The current Z.ai web flow observed in `prod-fe-1.1.79` is:

```text
GET  /api/models
POST /api/v1/chats/new
Alibaba Cloud Captcha success callback
POST /api/v2/chat/completions?...&signature_timestamp={timestamp}
```

`chats/new` receives the chat seed and returns the upstream chat ID. The completion request carries
the account token and browser fingerprint in its query, a one-time `captcha_verify_param` in the
JSON body, and `x-fe-version`, `x-region`, and `x-signature` headers.
The captcha page remains owned by one dedicated worker thread until the signed completion arrives.
Flows are session-scoped, single-use, bounded by a consumption timeout, and cleaned on cancellation.
This prevents `FRONTEND_CAPTCHA_REQUIRED`, which occurs when a ticket is solved in one browser and
submitted through a different TLS/browser fingerprint.

The signature contract is:

```text
sortedPayload = "requestId,{requestId},timestamp,{timestamp},user_id,{userId}"
bucket = floor(timestamp / 300000)
rotatingKey = HMAC-SHA256-hex(configuredKey, decimal(bucket))
message = sortedPayload + "|" + base64(UTF8(signaturePrompt)) + "|" + timestamp
x-signature = HMAC-SHA256-hex(rotatingKey, message)
```

This formula was recomputed against the supplied HAR and matched its 64-character `x-signature`.
The frontend version and signing key are typed provider settings with deployment overrides; they do
not appear in shared transport code.

The upstream stream uses `type=chat:completion`. `data.phase=thinking` maps to reasoning deltas,
`answer` maps to output text, `other` carries usage, and `done` completes the canonical response.
Both public Chat Completions and Responses consume this same canonical event stream.
The decoder also accepts equivalent non-SSE completion JSON and alternate textual delta fields.
Non-completion JSON is an explicit provider error instead of an empty successful response. When a
stream contains no answer deltas, diagnostics retain only byte/frame counts, event types, and phase
counts; response text and credentials are never logged.

## Captcha solver

Two provider-local profiles are required:

```text
authentication: SceneId=36qgs6xb, mode=embed
chat:           SceneId=didk33e0, mode=popup
```

For inpainting slider challenges, the SDK exposes a background and a separate transparent
foreground strip. Rendered scenes are not assumed to be square: current live challenges include
portrait images narrower than 240 pixels. The foreground strip keeps its native width and vertical
coordinate; stretching it to the background width destroys both the candidate geometry and the
reachable range.

The deterministic solver crops the alpha-masked foreground and compares sharp and Gaussian-blurred
templates against the source background at the foreground's fixed vertical band. It requires a
three-scale position consensus, sufficient blur-match gain, bounded candidate spread, low
Laplacian boundary-energy percentile, and a weak sharp-template match. These guards distinguish the
inpainted low-frequency residue from a visually similar object that is already present. Candidate
sheets are generated only as bounded diagnostics; they are not sent to a model.

An ambiguous OpenCV result, missing SDK source image, or unsupported non-slider visual layout is a
refresh decision, never an inferred coordinate. Browser attempts reuse the same mailbox and rotate
according to provider policy. The GLM captcha path does not call `/multimodal-random`; this prevents
low-quality AI guesses and removes recursive dependence on inference-ready provider accounts.
The SDK replaces slider DOM nodes in place, so capture retries the official image and handle as one
stable geometry pair. A vanished node is a transient round retry; the browser viewport is never
captured or submitted as a captcha image.

FeiLin does not map handle pixels linearly to foreground pixels. In a current live sample a 145 px
handle move produced only 85.8 px of foreground motion, matching an approximately quadratic curve.
The executor therefore calibrates the mapping during the same held drag, estimates the live curve,
inverts it for the selected scene coordinate, and corrects against the actual foreground DOM box
before releasing. No curve exponent is fixed in configuration. Other visual layouts use a bounded
click/drag action schema. Only the SDK success callback can accept a ticket, and a pending callback
is polled for up to 30 seconds rather than treated as an immediate failure.

GLM declares `registration_attempt_mode=single_identity`. Browser and captcha retries reuse the
same mailbox; Java caps the scheduler attempt budget to the requested identity count so an internal
captcha failure cannot silently allocate a replacement mailbox.

The observed email activation state machine is explicit and does not depend on the frontend page
eventually writing local storage:

```text
POST /api/v1/auths/signup
activation link from temporary mail
POST /api/v1/auths/verify_email
POST /api/v1/auths/finish_signup
GET  /api/v1/auths/
```

Activation links must use the configured provider host and match the exact mailbox. The nested
`user.token` returned by `finish_signup` is persisted only after the authenticated profile probe
succeeds.

## Current verification status

Verified on 2026-08-01:

- official chat-scene SDK initialization and a live 280-byte traceless ticket;
- registration navigation-time observation reuses the provider-owned captcha instance, so region,
  scene, prefix, and dynamic SDK behavior come from the current official frontend; configured
  values are only a bounded fallback when the official component does not initialize;
- authentication-scene escalation to real semantic challenges;
- a live authentication semantic-slider challenge accepted by the official SDK success callback;
- deterministic OpenCV placement using blur gain, three-scale consensus, Laplacian boundary energy,
  and sharp-template rejection;
- DOM foreground geometry, transparent-strip extraction, pure-background composition, and strict
  drag-only execution guards;
- live non-linear handle/foreground measurements and adaptive execution with final placement errors
  below one pixel on two rejected semantic labels;
- bounded diagnostic artifacts and strict refresh behavior for ambiguous or unsupported layouts;
- HAR-derived `verify_email` and `finish_signup` request/response mapping;
- request-signature equality against the supplied HAR;
- Java Chat/Responses mapping, chunked SSE decoding, model parsing, provider isolation, and random
  route regression tests;
- Python provider discovery, provider-local router discovery, deadline handling, coordinate mapping,
  and secret scanning.

Not yet accepted as production-ready:

- the new three-stage, provider-deduplicated semantic solver still needs an official SDK success
  callback in the deployed build and a repeated live stability matrix;
- no newly registered GLM account has completed activation and a real inference probe in Any2API;
- the Java/Python same-proxy chat-ticket path has protocol tests but no end-to-end account live test.

Until those checks pass, GLM registration returns `ready_for_inference=false` with
`inference_probe_required=true`. The account must not enter the inference pool.
