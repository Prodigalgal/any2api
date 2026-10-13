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
    private final ProviderRegistry providers;
    private volatile DecodedCatalog decodedCatalog;
    private static final String MODEL_QUERY = """
        SELECT m.upstream_id, m.display_name, m.provider_id, p.display_name AS provider_name,
               m.capabilities::text AS discovered_capabilities,
               m.max_context_tokens_override, m.max_input_tokens_override, m.max_output_tokens_override,
               m.catalog_source, m.metadata::text, m.random_roles, m.fetched_at
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
        Any2ApiProperties properties,
        ProviderRegistry providers
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.providers = providers;
        var policy = properties.getCache().getModelCatalog();
        // Isolate compressed snapshots from older processes during rolling releases.
        this.cache = new LayeredJsonCache(
            redis, "any2api:cache:model-catalog:v9", policy.getLocalTtl(),
            policy.getRedisTtl(), policy.getMaximumEntries(), properties.getCache().getRedisAccessTimeout());
    }

    public Mono<List<Entry>> list() {
        return cache.get("enabled", () -> Mono.fromCallable(() -> Optional.of(
                ModelCatalogSnapshotCodec.encode(mapper.writeValueAsString(load()))))
            .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(value -> value.map(this::decode).orElseGet(() -> Mono.just(List.of())));
    }

    public Mono<Optional<Entry>> find(String providerId, String modelId) {
        return list().map(entries -> entries.stream()
            .filter(entry -> entry.providerId().equals(providerId) && entry.id().equals(modelId))
            .findFirst());
    }

    public Mono<Void> invalidate() {
        decodedCatalog = null;
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

    private synchronized Mono<List<Entry>> decode(String value) {
        if (decodedCatalog != null && decodedCatalog.serialized().equals(value)) {
            return decodedCatalog.entries();
        }
        // Reuse only the currently returned L1/L2 snapshot; cache TTL and eviction
        // remain authoritative. Parsing runs once per snapshot off the event loop.
        var entries = Mono.<List<Entry>>fromCallable(() -> {
            var type = mapper.getTypeFactory().constructCollectionType(List.class, Entry.class);
            return List.copyOf(mapper.readValue(ModelCatalogSnapshotCodec.decode(value), type));
        }).subscribeOn(Schedulers.boundedElastic()).cache();
        decodedCatalog = new DecodedCatalog(value, entries);
        return entries;
    }

    private record DecodedCatalog(String serialized, Mono<List<Entry>> entries) {}

    private Entry row(ResultSet result, int rowNumber) throws SQLException {
        var fetchedAt = result.getObject("fetched_at", java.time.OffsetDateTime.class);
        var discoveredCapabilities = readJson(result.getString("discovered_capabilities"));
        var maxContextTokensOverride = nullableLong(result, "max_context_tokens_override");
        var maxInputTokensOverride = nullableLong(result, "max_input_tokens_override");
        var maxOutputTokensOverride = nullableLong(result, "max_output_tokens_override");
        var providerId = result.getString("provider_id");
        var upstreamId = result.getString("upstream_id");
        var displayName = result.getString("display_name");
        var metadata = readJson(result.getString("metadata"));
        var currentCapabilities = mapper.valueToTree(providers.requirePlugin(providerId)
            .modelContract(new DiscoveredModel(upstreamId, displayName, mapper.convertValue(metadata,
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { })))
            .asMap());
        return new Entry(
            upstreamId,
            displayName,
            providerId,
            result.getString("provider_name"),
            effectiveCapabilities(
                currentCapabilities,
                maxContextTokensOverride,
                maxInputTokensOverride,
                maxOutputTokensOverride),
            discoveredCapabilities,
            maxContextTokensOverride,
            maxInputTokensOverride,
            maxOutputTokensOverride,
            result.getString("catalog_source"),
            metadata,
            List.of((String[]) result.getArray("random_roles").getArray()),
            fetchedAt == null ? Instant.now().getEpochSecond() : fetchedAt.toEpochSecond());
    }

    private JsonNode readJson(String value) {
        return value == null || value.isBlank()
            ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : mapper.readTree(value);
    }

    private Long nullableLong(ResultSet result, String field) throws SQLException {
        var value = result.getObject(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    private JsonNode effectiveCapabilities(
        JsonNode discovered,
        Long maxContextTokens,
        Long maxInputTokens,
        Long maxOutputTokens
    ) {
        var effective = discovered != null && discovered.isObject()
            ? (tools.jackson.databind.node.ObjectNode) discovered.deepCopy()
            : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (effective.path("tools").path("function_calling").asBoolean(false)) {
            // Strict validation belongs to the running gateway, independently of discovery age.
            ((tools.jackson.databind.node.ObjectNode) effective.path("tools"))
                .put("strict", true).put("strict_validation", "gateway_schema_validation");
        }
        putOverride(effective, "max_context_tokens", maxContextTokens);
        putOverride(effective, "max_input_tokens", maxInputTokens);
        putOverride(effective, "max_output_tokens", maxOutputTokens);
        return effective;
    }

    private void putOverride(
        tools.jackson.databind.node.ObjectNode capabilities,
        String field,
        Long value
    ) {
        if (value != null) capabilities.put(field, value);
    }

    public record Entry(
        String id,
        String displayName,
        String providerId,
        String providerName,
        JsonNode capabilities,
        JsonNode discoveredCapabilities,
        Long maxContextTokensOverride,
        Long maxInputTokensOverride,
        Long maxOutputTokensOverride,
        String catalogSource,
        JsonNode metadata,
        List<String> randomRoles,
        long created
    ) {}
}
