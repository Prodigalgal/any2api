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
              browser, registration, captcha, proxy, mail, risk headers
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

Provider-specific paths hard-lock routing:

- `/grok/v1`
- `/mimo/v1`
- `/qwen/v1`
- `/longcat/v1`

The unified `/v1` route requires model names such as `grok/grok-4.5`. A conflict between path and model namespace returns HTTP 400. Cross-provider fallback is never implicit.

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

## Migration strategy

The first implementation uses remote compatibility adapters to existing services. Native Java adapters replace them incrementally:

1. Grok establishes the Java adapter and SSE pipeline.
2. MiMo establishes full Responses, tools, media, and session semantics.
3. LongCat adds its text protocol while browser registration moves to Python.
4. Qwen adds dynamic models, media uploads, session parents, and realtime risk headers.

Each cutover requires sanitized golden upstream fixtures, structural output comparison, failure mapping tests, and live stream/non-stream verification.

