# ADR 0001: Runtime Boundaries

- Status: Superseded in part by ADR 0005
- Date: 2026-07-26

## Decision

Any2API uses one Java/Spring Boot modular monolith, one Python/FastAPI automation monolith, and one independently deployable Next.js frontend. PostgreSQL and Redis are the only required middleware.

Java owns domain behavior and canonical provider semantics. Python owns browser and computer-vision execution but cannot write domain tables. For providers whose official browser runtime generates protected request metadata, ADR 0005 moves only the physical upstream transport into Python. TypeScript and Node.js are limited to the frontend.

## Consequences

- Frontend releases do not rebuild or restart Java.
- Provider protocol code must migrate from Node into Java with golden fixtures and remote compatibility adapters.
- Java/Python contracts require explicit versioning and idempotency.
- Internal code remains modular so resource evidence can justify a future split without requiring it now.
