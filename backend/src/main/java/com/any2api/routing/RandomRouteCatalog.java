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
