# ADR 0001: Runtime Boundaries

- Status: Accepted
- Date: 2026-07-26

## Decision

Any2API uses one Java/Spring Boot modular monolith, one Python/FastAPI automation monolith, and one independently deployable Next.js frontend. PostgreSQL and Redis are the only required middleware.

Java owns all domain and provider inference behavior. Python owns browser and computer-vision execution but cannot write domain tables. TypeScript and Node.js are limited to the frontend.

## Consequences

- Frontend releases do not rebuild or restart Java.
- Provider protocol code must migrate from Node into Java with golden fixtures and remote compatibility adapters.
- Java/Python contracts require explicit versioning and idempotency.
- Internal code remains modular so resource evidence can justify a future split without requiring it now.

