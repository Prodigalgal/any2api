package com.any2api.routing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.time.Instant;
import com.any2api.config.Any2ApiProperties;
import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.RandomModelRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RandomRouteCatalog {
    private final JdbcClient jdbc;
    private final java.time.Duration healthWindow;
    private final java.time.Duration probeFreshness;

    public RandomRouteCatalog(JdbcClient jdbc, Any2ApiProperties properties) {
        this.jdbc = jdbc;
        this.healthWindow = properties.getModelRuntime().getHealthWindow();
        this.probeFreshness = properties.getModelRuntime().getProbeFreshness();
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
              AND NOT EXISTS (
                  SELECT 1 FROM model_probe_results failed_probe
                  WHERE failed_probe.provider_id = model.provider_id
                    AND failed_probe.model_id = model.upstream_id
                    AND failed_probe.status = 'FAILED'
                    AND failed_probe.probed_at >= :probeFreshAfter
                    AND NOT EXISTS (
                        SELECT 1 FROM usage_events recovered
                        WHERE recovered.provider_id = model.provider_id
                          AND recovered.model_id = model.upstream_id
                          AND recovered.success = TRUE
                          AND recovered.created_at > failed_probe.probed_at
                    )
              )
              AND (
                  EXISTS (
                      SELECT 1 FROM usage_events usage
                      WHERE usage.provider_id = model.provider_id
                        AND usage.model_id = model.upstream_id
                        AND usage.success = TRUE
                        AND usage.created_at >= :windowStart
                  )
                  OR EXISTS (
                      SELECT 1 FROM model_probe_results probe
                      WHERE probe.provider_id = model.provider_id
                        AND probe.model_id = model.upstream_id
                        AND probe.status = 'READY'
                        AND probe.probed_at >= :probeFreshAfter
                  )
              )
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
            .param("windowStart", PostgresResultValues.timestamp(
                Instant.now().minus(healthWindow)))
            .param("probeFreshAfter", PostgresResultValues.timestamp(
                Instant.now().minus(probeFreshness)))
            .query(this::row)
            .list();
    }

    private ModelRoute row(ResultSet result, int rowNumber) throws SQLException {
        return new ModelRoute(
            result.getString("provider_id"), result.getString("upstream_id"));
    }

    public record ModelRoute(String providerId, String modelId) {}
}
