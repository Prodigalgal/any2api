package com.any2api.observability;

import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.persistence.PostgresResultValues;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OperationReconciliationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationReconciliationScheduler.class);
    private static final Duration STALE_GRACE = Duration.ofMinutes(30);
    private static final int BATCH_SIZE = 500;
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;

    public OperationReconciliationScheduler(JdbcClient jdbc, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Scheduled(
        initialDelayString = "${any2api.observability.reconcile-initial-delay:30s}",
        fixedDelayString = "${any2api.observability.reconcile-interval:1m}"
    )
    @Transactional
    public int reconcile() {
        if (!locks.tryLockTransaction("any2api-operation-reconciliation")) return 0;
        var updated = jdbc.sql("""
            WITH candidates AS (
                SELECT event.id FROM operation_events event
                WHERE event.status = 'RUNNING' AND event.started_at < :cutoff
                  AND event.domain IN ('LIFECYCLE', 'REGISTRATION')
                  AND NOT EXISTS (
                    SELECT 1 FROM scheduled_actions action
                    WHERE event.domain = 'LIFECYCLE'
                      AND action.provider_id = event.provider_id
                      AND action.entity_id = event.aggregate_id
                      AND action.action_family = event.operation
                      AND action.status = 'LEASED' AND action.lease_owner IS NOT NULL
                      AND action.lease_expires_at > CURRENT_TIMESTAMP
                      AND event.attempt = action.attempts + 1
                      AND event.started_at >= action.updated_at - INTERVAL '5 seconds'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM registration_jobs job
                    WHERE event.domain = 'REGISTRATION' AND job.id::text = event.aggregate_id
                      AND job.provider_id = event.provider_id AND job.status = 'RUNNING'
                      AND job.lease_owner IS NOT NULL AND job.lease_expires_at > CURRENT_TIMESTAMP
                      AND event.attempt > job.attempts
                      AND event.attempt <= job.attempts + job.concurrency
                  )
                ORDER BY event.started_at, event.id
                FOR UPDATE OF event SKIP LOCKED LIMIT :limit
            )
            UPDATE operation_events event
            SET status = 'FAILED', stage = 'reconciliation', error_code = 'execution_lease_missing',
                error_detail = 'operation has no matching live execution lease',
                finished_at = CURRENT_TIMESTAMP,
                duration_ms = GREATEST(0,
                    (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - event.started_at)) * 1000)::BIGINT)
            FROM candidates WHERE event.id = candidates.id AND event.status = 'RUNNING'
            """)
            .param("cutoff", PostgresResultValues.timestamp(Instant.now().minus(STALE_GRACE)))
            .param("limit", BATCH_SIZE).update();
        if (updated > 0) LOGGER.info("orphan_operations_reconciled count={}", updated);
        return updated;
    }
}
