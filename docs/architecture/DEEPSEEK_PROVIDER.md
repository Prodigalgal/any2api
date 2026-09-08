# DeepSeek Provider

> 迁移提示：本文保留 DeepSeek 当前 legacy provider 实现的细节。现行目标以
> [ADR-0006](../adr/0006-unified-camoufox-inference-runtime.md) 为准：Java 保留语义编排和
> canonical 解码，Python Camoufox Browser Runtime 承担上游物理请求；迁移完成前不得继续
> 扩展本文描述的 Java direct/curl-cffi 出站路径。

DeepSeek is a native Any2API provider. It does not proxy an older `*2api` service and it does not
add provider-specific branches to shared routing, account selection, lifecycle scheduling, or the
frontend.

## Runtime ownership

The Java provider package owns authenticated model discovery policy, OpenAI Chat Completions and
Responses translation, canonical event decoding, and error classification. The current legacy
implementation also contains upstream chat-session and `DeepSeekHashV1` request details; those
physical responsibilities move to the DeepSeek Camoufox Runtime in the next migration slice.

The Python provider package owns email registration, the official hCaptcha browser interaction,
temporary-mail OTP retrieval, birthday activation, password reauthentication, keepalive, browser
fingerprints, and registration-proxy affinity. A registration task creates one mailbox and retries
the browser flow with that same identity. Only exhaustion of the task's bounded browser budget lets
the Java registration scheduler create another task identity.

Before opening sign-up, the provider loads the official sign-in settings and confirms that hCaptcha
is enabled. Challenge handling waits for the real task image, removes prompt chrome, and maps
normalized click or drag actions back to the browser surface. The shared visual solver samples the
multimodal-random route independently; when the first vote has no geometric consensus, a bounded
second review inspects the image and candidate actions again. Completion is accepted only after the
official email-verification response arrives, never from an iframe disappearing temporarily.
Single-drag tasks use a bounded target-region tolerance. Multiple click or drag actions are sorted
by geometry before consensus so equivalent model answers do not disagree merely by action order;
the number of drag actions follows the current task instruction instead of being fixed to one.
Registration validates the outer envelope, business envelope, and nested registration result
separately. If all result codes succeed but the response omits the user object, the provider uses
the same browser, mailbox identity, generated password, device ID, and request profile for one
official login recovery before declaring the account unusable.

Registration traffic follows the provider's `REGISTRATION` proxy binding. Until migration,
inference and keepalive can use the legacy direct path and scoped proxy bindings. After migration,
both operations use the account Camoufox Runtime and its scoped proxy lease. Mail, Redis, Java
callbacks, and captcha inference never inherit the provider proxy.

## Protocol profile

The registration browser captures the official `X-Client-*` request profile that actually passed the
current upstream edge and stores it with that account. Inference prefers this observed account
profile. A fingerprinted, allowlisted official-asset refresher updates the process fallback when the
deployment egress can reach those assets; it retains the last verified value on WAF rejection,
timeout, or parse failure. The configured version is therefore only a cold-start fallback, not a
request-method constant. Account credentials also retain their own device ID, User-Agent, and
browser profile.

For each inference request the provider:

1. creates a fresh official chat session;
2. requests a proof-of-work challenge for `/api/v0/chat/completion`;
3. solves `DeepSeekHashV1` with the official 23-round Keccak variant on a bounded worker scheduler;
4. submits the mapped prompt and proof using the account's bearer token;
5. converts reasoning, response, usage, close, and failure patches to canonical events.

`default`, `expert`, and `vision` are only cold-start model IDs. Authenticated official discovery is
authoritative. `expert` is the current `top_text` preference. The provider deliberately does not
declare `top_multimodal` or image-input capability until the official upload and reference-file
sequence has a sanitized fixture and live acceptance evidence.

## Admission and recovery

An accepted registration is imported as `PENDING` and disabled. A real Java inference-readiness
probe must return the requested marker before the account becomes `ACTIVE`. Registration success is
therefore never treated as proof that the account can answer model requests.

Birthday activation uses bounded retries after account creation. If that endpoint is temporarily
unavailable, the token and mailbox credential are still persisted with a pending activation marker;
password reauthentication retries activation before another inference-readiness probe. Keepalive
requires a successful authenticated model catalog and treats HTTP 401/403 as expired authentication.

Secrets, OTPs, bearer tokens, mailbox JWTs, raw challenge images, HAR files, and proxy node URLs are
not written to provider logs or registration-job results. Durable credentials are stored only in the
encrypted account credential vault.
