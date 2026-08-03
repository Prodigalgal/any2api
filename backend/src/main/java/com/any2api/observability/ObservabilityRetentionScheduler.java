package com.any2api.observability;

import com.any2api.config.Any2ApiProperties;
import com.any2api.coordination.PostgresAdvisoryLocks;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ObservabilityRetentionScheduler {
    private static final int BATCH_SIZE = 5000;
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;
    private final Any2ApiProperties properties;

    public ObservabilityRetentionScheduler(
        JdbcClient jdbc,
        PostgresAdvisoryLocks locks,
        Any2ApiProperties properties
    ) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.properties = properties;
    }

    @Scheduled(
        fixedDelayString = "${any2api.observability.cleanup-interval:1h}",
        initialDelayString = "${any2api.observability.cleanup-initial-delay:10m}"
    )
    @Transactional
    public void cleanup() {
        if (!locks.tryLockTransaction("any2api-observability-retention")) return;
        deleteOperationEvents();
        deleteUsageEvents();
    }

    private void deleteOperationEvents() {
        var cutoff = Instant.now().minus(
            properties.getObservability().getOperationRetention());
        jdbc.sql("""
            DELETE FROM operation_events
            WHERE id IN (
                SELECT id FROM operation_events
                WHERE status <> 'RUNNING' AND started_at < :cutoff
                ORDER BY started_at LIMIT :batchSize
            )
            """).param("cutoff", cutoff).param("batchSize", BATCH_SIZE).update();
    }

    private void deleteUsageEvents() {
        var cutoff = Instant.now().minus(properties.getObservability().getUsageRetention());
        jdbc.sql("""
            DELETE FROM usage_events
            WHERE id IN (
                SELECT id FROM usage_events
                WHERE created_at < :cutoff
                ORDER BY created_at LIMIT :batchSize
            )
            """).param("cutoff", cutoff).param("batchSize", BATCH_SIZE).update();
    }
}
