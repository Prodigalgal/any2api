package com.any2api.protocol.state;

import com.any2api.coordination.PostgresAdvisoryLocks;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ResponseStateRetentionScheduler {
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;

    public ResponseStateRetentionScheduler(JdbcClient jdbc, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Scheduled(fixedDelayString="${any2api.responses.cleanup-interval:15m}", initialDelayString="${any2api.responses.cleanup-initial-delay:2m}")
    @Transactional
    public void cleanup() {
        if (!locks.tryLockTransaction("any2api-response-state-retention")) return;
        for (var table : java.util.List.of("gateway_responses", "provider_response_states")) {
            jdbc.sql("DELETE FROM " + table + " WHERE response_id IN (SELECT response_id FROM " + table
                + " WHERE expires_at <= CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 1000)").update();
        }
    }
}
