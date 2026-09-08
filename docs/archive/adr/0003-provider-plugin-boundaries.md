# ADR 0003: Provider plugin boundaries

## Status

Accepted for provider-isolation rules; its physical transport ownership paragraph is superseded by
ADR-0006.

## Decision

Any2API uses compile-time provider plugins inside two modular monoliths. Java plugins implement
`InferenceProvider`; Python plugins implement `AutomationProvider`. Runtime configuration may tune a
plugin but cannot synthesize an upstream protocol implementation.

Platform modules depend on provider-neutral SPIs. Concrete plugins depend on those SPIs and shared
infrastructure ports. Concrete providers do not depend on sibling providers, and shared modules do
not import provider settings or challenge behavior.

Fingerprint-sensitive HTTP is exposed by Python as an origin-allowlisted, relative-path-only
browser-session port. Python owns TLS/HTTP2 impersonation, cookie continuity, user-agent and client
hints, browser navigation/fetch request profiles, protected fingerprint-header ordering, and proxy
affinity. Java selects a typed fingerprint profile but cannot supply or override its managed
`Origin`, `Referer`, `Sec-Fetch-*`, `Priority`, cache, or browser accept headers.

Java owns canonical semantics, response/event contracts, quota semantics, and lifecycle decisions.
The Python Camoufox Browser Runtime owns provider paths, physical request headers, signing, request
bodies, raw response parsing, and browser state. The internal Runtime command is typed and
allowlisted; it cannot dispatch arbitrary provider URLs or execute arbitrary JavaScript. The old
opaque curl-cffi session remains a migration compatibility port only. See ADR-0006.

Java providers may implement `ProviderLifecycleHandler` for protocol-only keepalive and refresh
operations. The lifecycle executor resolves these handlers through a registry and falls back to
Python only for operations that require browser UI, captcha, mailbox, or registration automation.

Provider discovery is registry-driven and deterministic. Provider IDs and lifecycle operations are
validated at startup. Public provider directories, routing, account management, proxy binding, and
registration selectors consume registry manifests rather than hardcoded provider lists or inferred
model ownership.

The Java control plane periodically consumes the Python automation manifest. Lifecycle operations
are the intersection of the installed Java provider and Python's last valid declaration. A missing
or invalid automation manifest fails closed for new lifecycle work while retaining the last valid
snapshot on transient refresh failures.

PostgreSQL records code presence as `providers.installed` separately from administrative
`providers.enabled`. Plugin removal stops routing and queued work but preserves durable data.

## Consequences

- Adding a provider requires new provider files, contract fixtures, and tests, but no shared routing
  or UI branch.
- Removing a provider is reversible and does not delete accounts or credentials.
- Provider-specific request profiles and captcha strategies can evolve independently.
- Moving an operation from Python to Java does not change scheduler or catalog branches; local and
  automation lifecycle capabilities are merged through registries.
- Cross-provider fallback must be implemented as an explicit platform policy, never inside a plugin.
- A provider cannot be declared native until both Chat Completions and Responses contracts pass.
