# Architecture

## Runtime topology

Any2API has three independently deployable business applications and two middleware dependencies:

```text
Next.js Web
    |
    | admin HTTP
    v
Java Spring Boot Modular Monolith ---- PostgreSQL
    |          |                       Redis
    |          |
    |          +---- provider upstreams
    |
    +---- Python Automation Platform
              browser, registration, captcha, proxy, mail,
              official browser runtime transport
```

The Java application can run multiple replicas. Every replica contains all modules. PostgreSQL row claims and Redis leases coordinate duplicate schedulers and concurrent inference; module boundaries do not imply deployment boundaries.

## Request flow

```text
authenticate API key
resolve provider from path or namespaced model
validate standard parameters and provider_options schema
acquire session lock when needed
acquire account lease with fencing token
load a versioned credential snapshot
execute provider adapter
map upstream data to versioned canonical events
render Chat Completions or Responses
record usage and release the lease
```

Provider-specific paths use the discovered provider ID, for example `/acme/v1`. The unified `/v1`
route requires a namespaced model such as `acme/acme-ultra`. A conflict between path and model
namespace returns HTTP 400. Cross-provider fallback is never implicit.

For protected web providers, the Java adapter still owns the semantic request and canonical event
contract, while Python restores the encrypted account browser context and invokes the current
official frontend request module. This exception is capability-gated; stable documented APIs remain
native Java transports. See ADR 0005.

## High-level and low-level boundaries

The high-level platform owns canonical requests and events, routing, account leases, persistence,
scheduling, proxy allocation, protocol rendering, and plugin discovery. It depends only on the Java
`InferenceProvider` SPI and Python `AutomationProvider` SPI.

Each low-level provider plugin owns its manifest, typed settings, request profile, upstream client,
request mapper, event decoder, error classifier, registration flow, and challenge strategies. A
plugin may use platform ports and shared solver primitives, but the platform never imports a concrete
provider and one provider never imports another provider.

```text
OpenAI controllers / schedulers / catalog / lifecycle
                         |
                  provider SPI ports
                         ^
                         |
       isolated provider plugin (Java and Python)
```

Spring discovers Java `InferenceProvider` beans. Python discovers `AutomationProvider` subclasses
from the provider package. The registries validate IDs, capabilities, lifecycle operation names, and
duplicates before serving traffic.

## Persistence and coordination

PostgreSQL owns accounts, credentials, models, sessions, Responses state, API keys, jobs, lifecycle schedules, usage, and audit history. Liquibase owns schema evolution.

Redis owns only short-lived coordination:

- account semaphores and lease heartbeats;
- session serialization locks;
- API-key/provider token buckets;
- short-lived model and authorization caches;
- automation and domain event Streams.

Redis failure is fail-closed for operations requiring coordination. The application must not fall back to process-local locks in a multi-replica deployment.

## Architectural invariants

1. Java is the only writer of domain state.
2. Python reports step events and results through versioned contracts.
3. Every lease has a unique owner token, TTL, renewal, compare-and-release, and monotonic fencing token.
4. Every long-running command is idempotent and cancellable.
5. Every provider adapter declares capabilities and request schema versions.
6. Explicit unsupported parameters fail; they are never silently ignored.
7. Provider streams become canonical events before native adapters render OpenAI output.
8. Inference and lifecycle use the same account-capacity budget.
9. The public inference API exposes only OpenAI Chat Completions and Responses; provider plugins
   cannot introduce Anthropic Messages or another protocol surface.

## Plugin lifecycle

The durable provider row distinguishes `installed` from `enabled`. Startup reconciliation sets
`installed` from discovered code without changing the administrator's `enabled` choice. Removing a
plugin makes its models unavailable and terminates pending lifecycle and registration work, while
retaining accounts, credentials, models, and audit history for a later reinstall.

Each plugin change requires sanitized golden upstream fixtures, structural output comparison,
failure mapping tests, and live stream/non-stream verification.
