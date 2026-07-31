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

The shared vision client is configured only by environment variables. The API key is never stored
in source, examples, diagnostics, or challenge artifacts. The current development fallback uses an
OpenAI-compatible endpoint and model `mimo-v2.5`.

For inpainting slider challenges, the SDK exposes a 300x300 background and a separate transparent
foreground strip. The solver first computes local ddddocr/OpenCV offsets. It accepts local output
only when the estimates differ by at most 12 pixels. Otherwise MiMo receives the original composite
layer and selects among the measured pixel candidates. The browser anchors the action to
`#aliyunCaptcha-sliding-slider` and applies only the selected horizontal displacement. Other visual
layouts use a bounded click/drag action schema. Only the SDK success callback can accept a ticket.

## Current verification status

Verified on 2026-07-29:

- official chat-scene SDK initialization and a live 280-byte traceless ticket;
- authentication-scene escalation to real inpainting challenges;
- old MiMo service image transport and structured click/drag output;
- request-signature equality against the supplied HAR;
- Java Chat/Responses mapping, chunked SSE decoding, model parsing, provider isolation, and random
  route regression tests;
- Python provider discovery, provider-local router discovery, deadline handling, coordinate mapping,
  and secret scanning.

Not yet accepted as production-ready:

- authentication semantic-slider attempts have not reached the official success callback;
- the old MiMo endpoint has produced intermittent read timeouts and inconsistent semantic offsets;
- no newly registered GLM account has completed activation and a real inference probe in Any2API;
- the Java/Python same-proxy chat-ticket path has protocol tests but no end-to-end account live test.

Until those checks pass, GLM registration returns `ready_for_inference=false` with
`inference_probe_required=true`. The account must not enter the inference pool.
