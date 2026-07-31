# GLM Provider

## Ownership

The GLM provider is split by capability, not by deployment:

- Java owns model discovery, Chat/Responses mapping, request fingerprints, double-HMAC signing,
  upstream SSE decoding, account selection, leases, retries, and state transitions.
- Python owns registration, reauthentication, Alibaba Cloud Captcha browser execution, temporary
  mail, and the provider proxy lease.
- The provider-local captcha runtime may return a one-time chat ticket for an existing opaque
  browser-transport session. It never builds or sends the inference request.

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

## Captcha solver

Two provider-local profiles are required:

```text
authentication: SceneId=36qgs6xb, mode=embed
chat:           SceneId=didk33e0, mode=popup
```

The shared vision client is configured only by environment variables. The API key and the optional
shared prompt prefix are never stored in source, examples, diagnostics, or challenge artifacts.
Every semantic image is submitted to the internal `multimodal-random` endpoint several times. The
provider and model returned by the gateway are retained only as non-secret diagnostics.

For inpainting slider challenges, the SDK exposes a 300x300 background and a separate transparent
foreground strip. The solver reads the foreground center from the live DOM, magnifies the detached
object in a left panel, and presents the pure background in a separate 300x300 target panel. Local
pixel matchers never decide a GLM semantic slider because visually similar completed objects can
produce a false geometric consensus. Five random multimodal samples run concurrently with an
independent per-sample timeout. Valid actions are grouped by shape, then a minimum two-vote majority
cluster is accepted only when every normalized coordinate spans no more than six percent. The
median target x is combined with the DOM-derived object x and anchored to
`#aliyunCaptcha-sliding-slider`. Other visual layouts use a bounded click/drag action schema. Only
the SDK success callback can accept a ticket.

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

Verified on 2026-07-31:

- official chat-scene SDK initialization and a live 280-byte traceless ticket;
- authentication-scene escalation to real semantic challenges;
- a live authentication semantic-slider challenge accepted by the official SDK success callback;
- five-sample multimodal random aggregation against fixed known-target challenge images;
- DOM foreground geometry, transparent-strip extraction, pure-background composition, and strict
  drag-only execution guards;
- HAR-derived `verify_email` and `finish_signup` request/response mapping;
- request-signature equality against the supplied HAR;
- Java Chat/Responses mapping, chunked SSE decoding, model parsing, provider isolation, and random
  route regression tests;
- Python provider discovery, provider-local router discovery, deadline handling, coordinate mapping,
  and secret scanning.

Not yet accepted as production-ready:

- no newly registered GLM account has completed activation and a real inference probe in Any2API;
- the Java/Python same-proxy chat-ticket path has protocol tests but no end-to-end account live test.

Until those checks pass, GLM registration returns `ready_for_inference=false` with
`inference_probe_required=true`. The account must not enter the inference pool.
