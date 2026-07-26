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

## Shared browser platform

One Python service and image contains Camoufox, Patchright, Xvfb, provider plugins, solver libraries, sing-box, and temporary-mail clients. It separates work with internal lanes rather than microservices:

- realtime: reserved for latency-sensitive risk headers;
- reauth: account recovery, higher priority than registration;
- registration: bounded batch work;
- captcha: independent CPU/process semaphores.

Each attempt receives an isolated context or process, temporary profile, proxy lease, and artifact directory. Provider manifests choose process or context isolation.

## Challenge pipeline

```text
provider detects and captures challenge
solver profile selects local algorithms
solvers return normalized estimates
fusion calculates consensus and confidence
provider maps the solution into page coordinates/actions
provider observes success, refresh, or rejection
```

Local solvers include ddddocr text/slider, captcha-recognizer slider, OpenCV template/dots/icons, and ONNX Runtime. Provider profiles own thresholds and fusion weights. Turnstile remains browser-bound because its token depends on page, IP, UA, action, and short-lived state.

Only attempt metadata is durable by default. Raw challenge artifacts are opt-in, failure-only, secret-sanitized, and TTL-bound.

## Successful completion

Python submits extracted results to Java. A single Java transaction inserts or updates the account, stores encrypted credentials, completes the registration job, schedules the first probe, and writes an Outbox event. The job is not successful until a provider probe establishes that the account can enter the inference pool.

