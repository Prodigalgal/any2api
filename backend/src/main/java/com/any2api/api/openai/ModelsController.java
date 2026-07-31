package com.any2api.api.openai;

import com.any2api.provider.ProviderRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class ModelsController {

    private static final String MODEL_QUERY = """
        SELECT m.upstream_id, m.display_name, m.provider_id,
               p.display_name AS provider_name, m.capabilities::text,
               m.catalog_source, m.metadata::text, m.random_roles, m.fetched_at,
               p.installed AND p.enabled AND EXISTS (
                   SELECT 1 FROM accounts a
                   WHERE a.provider_id = m.provider_id
                     AND a.enabled = TRUE
                     AND a.status IN ('ACTIVE', 'DEGRADED')
                     AND (a.cooldown_until IS NULL OR a.cooldown_until <= CURRENT_TIMESTAMP)
                     AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP)
               ) AS available
        FROM models m
        JOIN providers p ON p.id = m.provider_id
        WHERE m.enabled = TRUE AND p.installed = TRUE AND p.enabled = TRUE
        """;

    private final ProviderRegistry registry;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ModelsController(ProviderRegistry registry, JdbcClient jdbc, ObjectMapper mapper) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping({"/v1/models", "/api/catalog/v1/models"})
    public Map<String, Object> models() {
        var data = jdbc.sql(MODEL_QUERY + " ORDER BY m.provider_id, m.upstream_id")
            .query(this::row)
            .list()
            .stream()
            .map(model -> response(model, true))
            .toList();
        return Map.of("object", "list", "data", data);
    }

    @GetMapping("/{providerId:[a-z][a-z0-9_-]{1,31}}/v1/models")
    public Map<String, Object> providerModels(@PathVariable String providerId) {
        registry.require(providerId);
        var data = jdbc.sql(MODEL_QUERY + " AND m.provider_id = :providerId ORDER BY m.upstream_id")
            .param("providerId", providerId)
            .query(this::row)
            .list()
            .stream()
            .map(model -> response(model, false))
            .toList();
        return Map.of("object", "list", "data", data);
    }

    private ModelRow row(ResultSet result, int rowNumber) throws SQLException {
        return new ModelRow(
            result.getString("upstream_id"),
            result.getString("display_name"),
            result.getString("provider_id"),
            result.getString("provider_name"),
            readJson(result.getString("capabilities")),
            result.getString("catalog_source"),
            readJson(result.getString("metadata")),
            List.of((String[]) result.getArray("random_roles").getArray()),
            result.getObject("fetched_at", java.time.OffsetDateTime.class),
            result.getBoolean("available"));
    }

    private Map<String, Object> response(ModelRow model, boolean namespaced) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", namespaced ? model.providerId() + "/" + model.id() : model.id());
        result.put("object", "model");
        result.put("created", model.fetchedAt() == null
            ? Instant.now().getEpochSecond() : model.fetchedAt().toEpochSecond());
        result.put("owned_by", model.providerId());
        result.put("name", model.displayName());
        result.put("provider_name", model.providerName());
        result.put("available", model.available());
        result.put("capabilities", model.capabilities());
        result.put("catalog_source", model.catalogSource());
        result.put("metadata", model.metadata());
        result.put("random_roles", model.randomRoles());
        return result;
    }

    private JsonNode readJson(String value) {
        return value == null || value.isBlank()
            ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : mapper.readTree(value);
    }

    private record ModelRow(
        String id,
        String displayName,
        String providerId,
        String providerName,
        JsonNode capabilities,
        String catalogSource,
        JsonNode metadata,
        List<String> randomRoles,
        java.time.OffsetDateTime fetchedAt,
        boolean available
    ) {}
}
