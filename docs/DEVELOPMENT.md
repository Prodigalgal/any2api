# Development Guide

## Engineering boundaries

1. Java is the only business-backend language. Provider inference adapters, OpenAI protocol rendering, account state, scheduling, registration orchestration, and persistence live in `backend/`.
2. TypeScript and Node.js are used only by the independently deployed Next.js frontend.
3. Python owns browser and computer-vision execution. It does not write domain tables and does not decide durable account state.
4. PostgreSQL is authoritative. Redis data must be disposable and reconstructable.
5. Liquibase is the only schema migration mechanism. Hibernate runs with `ddl-auto=validate`.
6. Provider-specific behavior is registered through manifests and adapters. Core routing must not branch on provider names.

## Repository workflow

Before changing behavior:

1. Identify the owning module in `docs/architecture/MODULES.md`.
2. Update the relevant contract when a public or cross-process shape changes.
3. Add a Liquibase changeset for every database change; never edit an executed changeset.
4. Add provider fixture tests for request fields, non-stream output, stream events, errors, reasoning, tools, and multimodal behavior affected by the change.
5. Run component tests, then the integration slice.

## Commands

Backend:

```powershell
cd backend
.\gradlew.bat clean test
.\gradlew.bat bootJar
```

Automation:

```powershell
cd automation
uv sync
uv run pytest
uv run ruff check .
```

Frontend:

```powershell
cd web
npm ci
npm run lint
npm run build
```

Integration dependencies:

```powershell
docker compose up -d postgres redis
```

## Configuration and secrets

- Secrets enter processes only through environment variables or Kubernetes Secrets.
- Never place provider API keys, cookies, passwords, OTPs, mail JWTs, or browser profiles in Git, Redis Streams, screenshots, logs, or test fixtures.
- Provider credentials stored in PostgreSQL are envelope-encrypted. Metadata suitable for filtering remains in ordinary columns or non-sensitive JSONB.
- Internal Java/Python calls require a separate scoped token. Public API keys cannot call internal endpoints.

## Database changes

Changesets live under `backend/src/main/resources/db/changelog`.

- Use YAML for changelog composition and formatted SQL for PostgreSQL-specific DDL.
- Use expand/backfill/contract for large or destructive changes.
- Give every index and constraint an explicit stable name.
- Use preconditions when a changeset relies on an extension or prior data state.
- CI must validate the changelog and apply it to an empty PostgreSQL database.

## WebFlux and blocking persistence

Spring Data JPA and JdbcClient are blocking. They must never execute on Reactor Netty event-loop threads. Application services use the configured virtual-thread executor for database transactions. Streaming provider calls remain reactive through WebClient and must propagate downstream cancellation upstream.

## Definition of done

A feature is complete only when its affected surface has evidence:

- Java: unit/integration tests and successful build.
- Python: solver/automation tests and capability health result.
- Frontend: build plus browser verification at desktop and mobile widths.
- Provider protocol: golden-fixture comparison and, before production claims, live stream and non-stream calls.
- Lifecycle/registration: durable job state, lease behavior, retry/cancel behavior, and an end-to-end account probe.

