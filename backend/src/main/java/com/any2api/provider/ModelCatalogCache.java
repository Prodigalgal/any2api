package com.any2api.provider;

import com.any2api.cache.LayeredJsonCache;
import com.any2api.config.Any2ApiProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelCatalogCache {
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
        ORDER BY m.provider_id, m.upstream_id
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LayeredJsonCache cache;

    public ModelCatalogCache(
        JdbcClient jdbc,
        ObjectMapper mapper,
        ReactiveStringRedisTemplate redis,
        Any2ApiProperties properties
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        var policy = properties.getCache().getModelCatalog();
        this.cache = new LayeredJsonCache(
            redis, "any2api:cache:model-catalog:v1", policy.getLocalTtl(),
            policy.getRedisTtl(), policy.getMaximumEntries());
    }

    public Mono<List<Entry>> list() {
        return cache.get("enabled", () -> Mono.fromCallable(() -> Optional.of(
                mapper.writeValueAsString(load())))
            .subscribeOn(Schedulers.boundedElastic()))
            .map(value -> value.map(this::decode).orElseGet(List::of));
    }

    public Mono<Void> invalidate() {
        return cache.evict("enabled");
    }

    public void invalidateAfterCommit() {
        Runnable action = () -> invalidate().subscribe();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
    }

    private List<Entry> load() {
        return jdbc.sql(MODEL_QUERY).query(this::row).list();
    }

    private List<Entry> decode(String value) {
        var type = mapper.getTypeFactory().constructCollectionType(List.class, Entry.class);
        return mapper.readValue(value, type);
    }

    private Entry row(ResultSet result, int rowNumber) throws SQLException {
        var fetchedAt = result.getObject("fetched_at", java.time.OffsetDateTime.class);
        return new Entry(
            result.getString("upstream_id"),
            result.getString("display_name"),
            result.getString("provider_id"),
            result.getString("provider_name"),
            readJson(result.getString("capabilities")),
            result.getString("catalog_source"),
            readJson(result.getString("metadata")),
            List.of((String[]) result.getArray("random_roles").getArray()),
            fetchedAt == null ? Instant.now().getEpochSecond() : fetchedAt.toEpochSecond(),
            result.getBoolean("available"));
    }

    private JsonNode readJson(String value) {
        return value == null || value.isBlank()
            ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : mapper.readTree(value);
    }

    public record Entry(
        String id,
        String displayName,
        String providerId,
        String providerName,
        JsonNode capabilities,
        String catalogSource,
        JsonNode metadata,
        List<String> randomRoles,
        long created,
        boolean available
    ) {}
}
