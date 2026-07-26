# any2api

`any2api` consolidates Grok, MiMo, Qwen, and LongCat web-account gateways behind one OpenAI-compatible API and one operational control plane.

The repository is a deliberately small distributed system:

- `backend/`: one modular-monolith Java/Spring Boot service for inference, administration, scheduling, lifecycle, and persistence.
- `web/`: one independently deployable Next.js/React/MUI administration application.
- `automation/`: one Python/FastAPI service for browser automation, registration, proxy allocation, temporary mail, Qwen risk headers, and captcha solving.
- PostgreSQL: durable system of record, managed only through Liquibase changesets.
- Redis: rebuildable coordination for leases, locks, rate limits, streams, and caches.

## First vertical slice

The initial implementation provides:

- Provider manifests and route resolution for `/v1` and `/{provider}/v1`.
- Compatibility forwarding to existing provider services through environment configuration.
- PostgreSQL foundation schema and seed data through Liquibase.
- Redis-ready lease and scheduling contracts.
- Automation health and solver capability APIs with lazy model loading.
- A Next.js operational overview that is deployed independently of Java.

Provider-native Java protocol adapters will replace compatibility forwarding one at a time after golden-fixture and live protocol parity checks.

## Local development

Prerequisites:

- Java 25
- Gradle 9+
- Node.js 22+
- Python 3.12+
- Docker with Compose for PostgreSQL and Redis

Copy `.env.example` to `.env`, set local-only credentials, and start dependencies:

```powershell
docker compose up -d postgres redis
```

Start each application in its own terminal:

```powershell
cd backend
.\gradlew.bat bootRun
```

```powershell
cd automation
uv sync
uv run uvicorn any2api_automation.main:app --host 0.0.0.0 --port 8090
```

```powershell
cd web
npm ci
npm run dev
```

Default development addresses:

- Web: `http://localhost:3000`
- Java API: `http://localhost:8080`
- Python automation: `http://localhost:8090`

See [Development Guide](docs/DEVELOPMENT.md) and [Architecture](docs/architecture/ARCHITECTURE.md).
Provider and model onboarding is specified in
[Provider Extension Contract](docs/architecture/PROVIDER_EXTENSION.md).

Production container images target `linux/arm64`; GitHub Actions publishes one immutable tag per
component and source commit.
