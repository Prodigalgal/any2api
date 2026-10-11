package com.any2api.lifecycle;

import com.any2api.account.AccountRepository;
import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.OperationContext;
import com.any2api.observability.OperationEventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodically detects and purges unrecoverable dead accounts to prevent wasted system resources,
 * useless keepalive/probe traffic, and table bloat.
 */
@Component
public class AccountPurgeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountPurgeScheduler.class);

    private final AccountRepository accounts;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OperationEventService observability;
    private final Any2ApiProperties properties;

    public AccountPurgeScheduler(
        AccountRepository accounts,
        JdbcClient jdbc,
        TransactionTemplate transactions,
        OperationEventService observability,
        Any2ApiProperties properties
    ) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.observability = observability;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${any2api.lifecycle.account-purge.check-interval:1h}")
    public void poll() {
        try {
            var purged = purgeDeadAccounts();
            if (purged > 0) {
                LOGGER.info("dead_accounts_purge_completed purged_count={}", purged);
            }
        } catch (Exception e) {
            LOGGER.error("dead_accounts_purge_failed error={}", e.getMessage(), e);
        }
    }

    public int purgeDeadAccounts() {
        var purgeConfig = properties.getLifecycle().getAccountPurge();
        if (!purgeConfig.isEnabled()) {
            return 0;
        }

        var retentionThreshold = Instant.now().minus(purgeConfig.getRetentionPeriod());
        var failureThreshold = purgeConfig.getFailureThreshold();
        var batchSize = purgeConfig.getBatchSize();

        var deadAccountIds = accounts.findDeadAccountIds(retentionThreshold, failureThreshold, batchSize);
        if (deadAccountIds.isEmpty()) {
            return 0;
        }

        LOGGER.info("dead_accounts_detected count={} ids={}", deadAccountIds.size(), deadAccountIds);

        var purgedCount = transactions.execute(status -> {
            var deadIdStrings = deadAccountIds.stream().map(UUID::toString).toList();

            // 1. Unlink session references so foreign keys don't block deletion
            jdbc.sql("UPDATE sessions SET account_id = NULL WHERE account_id IN (:ids)")
                .param("ids", deadAccountIds)
                .update();

            // 2. Remove orphan scheduled actions for these accounts
            jdbc.sql("DELETE FROM scheduled_actions WHERE entity_type = 'ACCOUNT' AND entity_id IN (:ids)")
                .param("ids", deadIdStrings)
                .update();

            // 3. Remove model cooldown records
            jdbc.sql("DELETE FROM account_model_cooldowns WHERE account_id IN (:ids)")
                .param("ids", deadAccountIds)
                .update();

            // 4. Delete the dead accounts (cascades to account_credentials, responses, etc.)
            accounts.deleteAllById(deadAccountIds);

            return deadAccountIds.size();
        });

        if (purgedCount != null && purgedCount > 0) {
            var context = new OperationContext(UUID.randomUUID().toString(), "SYSTEM", "ACCOUNT_PURGE", 1);
            var observed = observability.start("LIFECYCLE", "system", "purge_dead_accounts", context);
            observability.succeed(observed, "purged_" + purgedCount);
        }

        return purgedCount != null ? purgedCount : 0;
    }
}
