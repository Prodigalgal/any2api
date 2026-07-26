# ADR 0002: Persistence and Coordination

- Status: Accepted
- Date: 2026-07-26

## Decision

PostgreSQL is authoritative. Spring Data JPA handles aggregate persistence and JdbcClient/NamedParameterJdbcTemplate handles PostgreSQL-specific atomic claims, upserts, and batches. Liquibase is the exclusive schema migration system. Redis provides disposable distributed coordination.

## Consequences

- Hibernate uses `ddl-auto=validate` and `open-in-view=false`.
- JDBC work is isolated from Reactor Netty event loops on virtual-thread executors.
- Redis loss cannot fall back to local locks for coordinated operations.
- Durable commands use PostgreSQL state plus Outbox; Redis Streams are at-least-once delivery.

