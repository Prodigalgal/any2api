package com.any2api.lifecycle;

import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.persistence.PostgresResultValues;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifecycleScheduleService {
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;

    public LifecycleScheduleService(JdbcClient jdbc, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Transactional
    public void scheduleInitialProbe(UUID accountId, String providerId) {
        scheduleInitialProbe(accountId, providerId, Duration.ofMinutes(2));
    }

    @Transactional
    public void scheduleInitialProbe(
        UUID accountId,
        String providerId,
        Duration spread
    ) {
        lifecycleLock(accountId, providerId);
        var active = activeCount(accountId, providerId, null);
        if (active > 0) return;
        var generation = nextGeneration(accountId, providerId);
        schedule(accountId, providerId, "keepalive", generation, Instant.now().plus(
            deterministicJitter(accountId, generation, spread)));
    }

    @Transactional
    public void rescheduleProbe(UUID accountId, String providerId, Duration spread) {
        lifecycleLock(accountId, providerId);
        var generation = nextGeneration(accountId, providerId);
        var dueAt = Instant.now().plus(deterministicJitter(accountId, generation, spread));
        var updated = jdbc.sql("""
            UPDATE scheduled_actions
            SET due_at = :dueAt, attempts = 0, last_error_class = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT'
              AND entity_id = :entityId AND action_family = 'keepalive'
              AND status = 'PENDING'
            """)
            .param("dueAt", PostgresResultValues.timestamp(dueAt))
            .param("providerId", providerId)
            .param("entityId", accountId.toString())
            .update();
        if (updated > 0 || activeCount(accountId, providerId, "keepalive") > 0) return;
        schedule(accountId, providerId, "keepalive", generation, dueAt);
    }

    @Transactional
    public void scheduleReauthentication(UUID accountId, String providerId) {
        scheduleReauthentication(accountId, providerId, Duration.ofMinutes(5));
    }

    @Transactional
    public void scheduleReauthentication(
        UUID accountId,
        String providerId,
        Duration spread
    ) {
        lifecycleLock(accountId, providerId);
        var active = activeCount(accountId, providerId, "reauthenticate");
        if (active > 0) return;
        jdbc.sql("""
            UPDATE scheduled_actions SET status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT' AND entity_id = :entityId
              AND action_family = 'keepalive' AND status = 'PENDING'
            """).param("providerId", providerId).param("entityId", accountId.toString())
            .update();
        var generation = nextGeneration(accountId, providerId);
        schedule(accountId, providerId, "reauthenticate", generation,
            Instant.now().plus(deterministicJitter(accountId, generation, spread)));
    }

    @Transactional
    public void scheduleRecoveryProbe(
        UUID accountId,
        String providerId,
        Duration spread
    ) {
        lifecycleLock(accountId, providerId);
        jdbc.sql("""
            UPDATE scheduled_actions SET status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT' AND entity_id = :entityId
              AND action_family = 'reauthenticate' AND status = 'PENDING'
            """).param("providerId", providerId).param("entityId", accountId.toString())
            .update();
        var generation = nextGeneration(accountId, providerId);
        var dueAt = Instant.now().plus(deterministicJitter(accountId, generation, spread));
        var updated = jdbc.sql("""
            UPDATE scheduled_actions
            SET due_at = :dueAt, attempts = 0, last_error_class = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT'
              AND entity_id = :entityId AND action_family = 'keepalive'
              AND status = 'PENDING'
            """)
            .param("dueAt", PostgresResultValues.timestamp(dueAt))
            .param("providerId", providerId)
            .param("entityId", accountId.toString())
            .update();
        if (updated > 0 || activeCount(accountId, providerId, "keepalive") > 0) return;
        schedule(accountId, providerId, "keepalive", generation, dueAt);
    }

    private void lifecycleLock(UUID accountId, String providerId) {
        locks.lockTransaction(providerId + ":" + accountId + ":lifecycle");
    }

    private long activeCount(UUID accountId, String providerId, String action) {
        var sql = """
            SELECT COUNT(*) FROM scheduled_actions
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT' AND entity_id = :entityId
              AND status IN ('PENDING', 'LEASED')
            """ + (action == null ? "" : " AND action_family = :action");
        var query = jdbc.sql(sql).param("providerId", providerId)
            .param("entityId", accountId.toString());
        if (action != null) query = query.param("action", action);
        return query.query(Long.class).single();
    }

    private long nextGeneration(UUID accountId, String providerId) {
        return jdbc.sql("""
            SELECT COALESCE(MAX(generation), 0) + 1 FROM scheduled_actions
            WHERE provider_id = :providerId AND entity_type = 'ACCOUNT' AND entity_id = :entityId
            """).param("providerId", providerId).param("entityId", accountId.toString())
            .query(Long.class).single();
    }

    void schedule(UUID accountId, String providerId, String action, long generation, Instant dueAt) {
        jdbc.sql("""
            INSERT INTO scheduled_actions(
                id, handler, provider_id, entity_type, entity_id, action_family,
                priority, due_at, generation, status, idempotency_key, payload)
            VALUES (
                :id, 'provider-automation', :providerId, 'ACCOUNT', :entityId, :action,
                0, :dueAt, :generation, 'PENDING', :idempotencyKey, '{}'::jsonb)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
            .param("id", UUID.randomUUID())
            .param("providerId", providerId)
            .param("entityId", accountId.toString())
            .param("action", action)
            .param("dueAt", PostgresResultValues.timestamp(dueAt))
            .param("generation", generation)
            .param("idempotencyKey", "account:" + accountId + ":" + action + ":" + generation)
            .update();
    }

    static Duration deterministicJitter(UUID accountId, long generation, Duration window) {
        var bound = Math.max(1, window.toMillis());
        var hash = 31L * accountId.hashCode() + generation;
        return Duration.ofMillis(Math.floorMod(hash, bound));
    }
}
