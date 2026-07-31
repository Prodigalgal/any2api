package com.any2api.routing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import com.any2api.provider.RandomModelRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RandomRouteCatalog {
    private final JdbcClient jdbc;

    public RandomRouteCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ModelRoute> installedModels(RandomModelRole role) {
        return jdbc.sql("""
            SELECT model.provider_id, model.upstream_id
            FROM models model
            JOIN providers provider ON provider.id = model.provider_id
            WHERE model.enabled = TRUE
              AND provider.enabled = TRUE
              AND provider.installed = TRUE
              AND :role = ANY(model.random_roles)
              AND EXISTS (
                  SELECT 1
                  FROM accounts account
                  WHERE account.provider_id = provider.id
                    AND account.enabled = TRUE
                    AND account.status IN ('ACTIVE', 'DEGRADED')
                    AND (account.cooldown_until IS NULL
                        OR account.cooldown_until <= CURRENT_TIMESTAMP)
                    AND (account.expires_at IS NULL
                        OR account.expires_at > CURRENT_TIMESTAMP)
                    AND NOT EXISTS (
                        SELECT 1
                        FROM account_model_cooldowns cooldown
                        WHERE cooldown.account_id = account.id
                          AND cooldown.provider_id = provider.id
                          AND cooldown.model_id = model.upstream_id
                          AND cooldown.cooldown_until > CURRENT_TIMESTAMP
                    )
              )
            ORDER BY model.provider_id, model.upstream_id
            """)
            .param("role", role.catalogValue())
            .query(this::row)
            .list();
    }

    private ModelRoute row(ResultSet result, int rowNumber) throws SQLException {
        return new ModelRoute(
            result.getString("provider_id"), result.getString("upstream_id"));
    }

    public record ModelRoute(String providerId, String modelId) {}
}
