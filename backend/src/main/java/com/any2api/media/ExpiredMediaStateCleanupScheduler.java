package com.any2api.media;

import com.any2api.coordination.PostgresAdvisoryLocks;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExpiredMediaStateCleanupScheduler {
    private static final int BATCH_SIZE = 5000;
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;

    public ExpiredMediaStateCleanupScheduler(JdbcClient jdbc, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Scheduled(
        fixedDelayString = "${any2api.media.cleanup-interval:15m}",
        initialDelayString = "${any2api.media.cleanup-initial-delay:2m}"
    )
    @Transactional
    public void cleanup() {
        if (!locks.tryLockTransaction("any2api-expired-media-state")) return;
        jdbc.sql("""
            DELETE FROM media_assets
            WHERE id IN (
                SELECT id FROM media_assets
                WHERE expires_at <= CURRENT_TIMESTAMP
                ORDER BY expires_at
                LIMIT :batchSize
            )
            """).param("batchSize", BATCH_SIZE).update();
        jdbc.sql("""
            DELETE FROM account_model_cooldowns
            WHERE (account_id, provider_id, model_id) IN (
                SELECT account_id, provider_id, model_id
                FROM account_model_cooldowns
                WHERE cooldown_until <= CURRENT_TIMESTAMP
                ORDER BY cooldown_until
                LIMIT :batchSize
            )
            """).param("batchSize", BATCH_SIZE).update();
    }
}
