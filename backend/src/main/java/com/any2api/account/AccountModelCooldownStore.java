package com.any2api.account;

import com.any2api.persistence.PostgresResultValues;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountModelCooldownStore {
    private final JdbcClient jdbc;

    public AccountModelCooldownStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Set<UUID> coolingAccounts(String providerId, String modelId) {
        return Set.copyOf(jdbc.sql("""
            SELECT account_id
            FROM account_model_cooldowns
            WHERE provider_id = :providerId AND model_id = :modelId
              AND cooldown_until > CURRENT_TIMESTAMP
            """)
            .param("providerId", providerId)
            .param("modelId", modelId)
            .query(UUID.class)
            .list());
    }

    @Transactional
    public void cooldown(
        UUID accountId,
        String providerId,
        String modelId,
        String reason,
        Duration duration
    ) {
        var now = Instant.now();
        jdbc.sql("""
            INSERT INTO account_model_cooldowns(
                account_id, provider_id, model_id, reason, cooldown_until, updated_at)
            VALUES (:accountId, :providerId, :modelId, :reason, :until, :now)
            ON CONFLICT (account_id, provider_id, model_id) DO UPDATE SET
                reason = EXCLUDED.reason,
                cooldown_until = GREATEST(
                    account_model_cooldowns.cooldown_until, EXCLUDED.cooldown_until),
                updated_at = EXCLUDED.updated_at
            """)
            .param("accountId", accountId)
            .param("providerId", providerId)
            .param("modelId", modelId)
            .param("reason", summarize(reason))
            .param("until", PostgresResultValues.timestamp(now.plus(duration)))
            .param("now", PostgresResultValues.timestamp(now))
            .update();
    }

    @Transactional
    public void clear(UUID accountId, String providerId, String modelId) {
        jdbc.sql("""
            DELETE FROM account_model_cooldowns
            WHERE account_id = :accountId
              AND provider_id = :providerId AND model_id = :modelId
            """)
            .param("accountId", accountId)
            .param("providerId", providerId)
            .param("modelId", modelId)
            .update();
    }

    private String summarize(String value) {
        if (value == null || value.isBlank()) return "provider rate limit";
        return value.substring(0, Math.min(1000, value.length()));
    }
}
