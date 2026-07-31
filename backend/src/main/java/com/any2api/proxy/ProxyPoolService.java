package com.any2api.proxy;

import com.any2api.credential.SecretCipher;
import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.ProviderInstallationCatalog;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProxyPoolService {
    private static final int MAX_NODES = 500;
    private static final TypeReference<Map<String, Object>> SECRET_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;
    private final ProviderInstallationCatalog providers;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;

    public ProxyPoolService(
        JdbcClient jdbc,
        PostgresAdvisoryLocks locks,
        ProviderInstallationCatalog providers,
        SecretCipher cipher,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.providers = providers;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProxyPoolView> list() {
        return jdbc.sql("SELECT * FROM proxy_pools ORDER BY name, id")
            .query(this::viewRow).list();
    }

    @Transactional
    public ProxyPoolView create(SaveCommand command) {
        var id = UUID.randomUUID();
        var mode = Mode.parse(command.mode());
        var secret = secret(mode, command.source());
        var sealed = cipher.seal(mapper.writeValueAsBytes(secret), aad(id, 1));
        jdbc.sql("""
            INSERT INTO proxy_pools(
                id, name, mode, enabled, encrypted_payload, nonce,
                algorithm, key_version, revision, node_count)
            VALUES (:id, :name, :mode, :enabled, :payload, :nonce,
                'AES-256-GCM', :keyVersion, 1, :nodeCount)
            """)
            .param("id", id).param("name", name(command.name())).param("mode", mode.name())
            .param("enabled", command.enabled() == null || command.enabled())
            .param("payload", sealed.encrypted()).param("nonce", sealed.nonce())
            .param("keyVersion", sealed.keyVersion())
            .param("nodeCount", nodeCount(secret)).update();
        replaceBindings(id, command.providerIds(), command.bindingScopes());
        return get(id);
    }

    @Transactional
    public ProxyPoolView update(UUID id, SaveCommand command) {
        var current = row(id);
        var mode = Mode.parse(command.mode());
        var revision = current.revision() + 1;
        var sourceProvided = command.source() != null && !command.source().isBlank();
        if (!sourceProvided && mode != current.mode()) {
            throw new IllegalArgumentException("proxy source is required when changing pool mode");
        }
        var secret = sourceProvided ? secret(mode, command.source()) : decrypt(current);
        var sealed = cipher.seal(mapper.writeValueAsBytes(secret), aad(id, revision));
        jdbc.sql("""
            UPDATE proxy_pools SET name = :name, mode = :mode, enabled = :enabled,
                encrypted_payload = :payload, nonce = :nonce, key_version = :keyVersion,
                revision = :revision, node_count = :nodeCount, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """)
            .param("name", name(command.name())).param("mode", mode.name())
            .param("enabled", command.enabled() == null || command.enabled())
            .param("payload", sealed.encrypted()).param("nonce", sealed.nonce())
            .param("keyVersion", sealed.keyVersion()).param("revision", revision)
            .param("nodeCount", nodeCount(secret)).param("id", id).update();
        replaceBindings(id, command.providerIds(), command.bindingScopes());
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        row(id);
        jdbc.sql("DELETE FROM provider_proxy_bindings WHERE proxy_pool_id = :id")
            .param("id", id).update();
        jdbc.sql("DELETE FROM proxy_pools WHERE id = :id").param("id", id).update();
    }

    @Transactional
    public ProxyPoolView upsertBootstrapNodePool(String poolName, String source) {
        locks.lockTransaction("proxy-bootstrap:" + name(poolName));
        var existing = jdbc.sql("SELECT id FROM proxy_pools WHERE name = :name")
            .param("name", name(poolName)).query(UUID.class).optional();
        if (existing.isEmpty()) {
            return create(new SaveCommand(
                poolName, Mode.NODE_LIST.name(), true, source, List.of(), Map.of()));
        }
        var id = existing.get();
        return update(id, new SaveCommand(
            poolName, Mode.NODE_LIST.name(), true, source, bindingIds(id), bindingScopes(id)));
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> runtimeForProvider(
        String providerId,
        ProxyTrafficScope scope
    ) {
        return jdbc.sql("""
            SELECT pool.* FROM proxy_pools pool
            JOIN provider_proxy_bindings binding ON binding.proxy_pool_id = pool.id
            WHERE binding.provider_id = :providerId AND pool.enabled = TRUE
              AND :scope = ANY(binding.traffic_scopes)
            """).param("providerId", providerId).param("scope", scope.name())
            .query(this::mapRow).optional()
            .map(row -> {
                var secret = decrypt(row);
                return row.mode() == Mode.SUBSCRIPTION_URL
                    ? Map.<String, Object>of(
                        "mode", row.mode().name(),
                        "subscription_url", String.valueOf(secret.get("subscription_url")))
                    : Map.<String, Object>of(
                        "mode", row.mode().name(),
                        "nodes", secret.get("nodes"));
            });
    }

    private ProxyPoolView get(UUID id) {
        return view(row(id));
    }

    private PoolRow row(UUID id) {
        return jdbc.sql("SELECT * FROM proxy_pools WHERE id = :id").param("id", id)
            .query(this::mapRow).optional()
            .orElseThrow(() -> new IllegalArgumentException("unknown proxy pool: " + id));
    }

    private PoolRow mapRow(ResultSet row, int ignored) throws SQLException {
        return new PoolRow(
            row.getObject("id", UUID.class), row.getString("name"),
            Mode.parse(row.getString("mode")), row.getBoolean("enabled"),
            row.getBytes("encrypted_payload"), row.getBytes("nonce"),
            row.getInt("key_version"), row.getLong("revision"), row.getInt("node_count"),
            PostgresResultValues.instant(row, "created_at"),
            PostgresResultValues.instant(row, "updated_at"));
    }

    private ProxyPoolView viewRow(ResultSet row, int ignored) throws SQLException {
        return view(mapRow(row, ignored));
    }

    private ProxyPoolView view(PoolRow row) {
        var scopes = bindingScopes(row.id());
        return new ProxyPoolView(
            row.id(), row.name(), row.mode().name(), row.enabled(), row.nodeCount(),
            true, List.copyOf(scopes.keySet()), scopes, row.createdAt(), row.updatedAt());
    }

    private List<String> bindingIds(UUID poolId) {
        return jdbc.sql("""
            SELECT provider_id FROM provider_proxy_bindings
            WHERE proxy_pool_id = :id ORDER BY provider_id
            """).param("id", poolId).query(String.class).list();
    }

    private Map<String, List<String>> bindingScopes(UUID poolId) {
        var result = new LinkedHashMap<String, List<String>>();
        jdbc.sql("""
            SELECT provider_id, traffic_scopes FROM provider_proxy_bindings
            WHERE proxy_pool_id = :id ORDER BY provider_id
            """).param("id", poolId).query((row, ignored) -> Map.entry(
                row.getString("provider_id"),
                List.of((String[]) row.getArray("traffic_scopes").getArray()))).list()
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private void replaceBindings(
        UUID poolId,
        List<String> providerIds,
        Map<String, List<String>> bindingScopes
    ) {
        var requested = new LinkedHashMap<String, List<String>>();
        if (bindingScopes != null) {
            bindingScopes.forEach((providerId, scopes) -> {
                var id = providerId == null ? "" : providerId.trim();
                if (id.isBlank() || scopes == null || scopes.isEmpty()) return;
                providers.requireInstalled(id);
                var normalizedScopes = scopes.stream().map(ProxyTrafficScope::parse)
                    .map(Enum::name).distinct().sorted().toList();
                requested.put(id, normalizedScopes);
            });
        }
        if (requested.isEmpty()) {
            for (var providerId : providerIds == null ? List.<String>of() : providerIds) {
                var id = providerId == null ? "" : providerId.trim();
                if (id.isBlank()) continue;
                providers.requireInstalled(id);
                requested.put(id, List.of(ProxyTrafficScope.REGISTRATION.name()));
            }
        }
        if (requested.isEmpty()) {
            jdbc.sql("DELETE FROM provider_proxy_bindings WHERE proxy_pool_id = :poolId")
                .param("poolId", poolId).update();
            return;
        }
        jdbc.sql("""
            DELETE FROM provider_proxy_bindings
            WHERE proxy_pool_id = :poolId AND provider_id NOT IN (:providerIds)
            """).param("poolId", poolId).param("providerIds", requested.keySet()).update();
        for (var entry : requested.entrySet()) {
            jdbc.sql("""
                INSERT INTO provider_proxy_bindings(provider_id, proxy_pool_id, traffic_scopes)
                VALUES (:providerId, :poolId, :scopes)
                ON CONFLICT (provider_id) DO UPDATE SET proxy_pool_id = EXCLUDED.proxy_pool_id,
                    traffic_scopes = EXCLUDED.traffic_scopes,
                    updated_at = CURRENT_TIMESTAMP
                """).param("providerId", entry.getKey()).param("poolId", poolId)
                .param("scopes", entry.getValue().toArray(String[]::new)).update();
        }
    }

    private Map<String, Object> decrypt(PoolRow row) {
        var plaintext = cipher.open(row.encryptedPayload(), row.nonce(),
            aad(row.id(), row.revision()));
        return mapper.readValue(plaintext, SECRET_TYPE);
    }

    private static Map<String, Object> secret(Mode mode, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("proxy source is required");
        }
        if (mode == Mode.SUBSCRIPTION_URL) {
            var value = source.trim();
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("proxy subscription URL is invalid", error);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("proxy subscription must be an HTTPS URL");
            }
            return Map.of("subscription_url", value);
        }
        var nodes = Arrays.stream(source.split("\\R"))
            .map(String::trim).filter(value -> !value.isBlank() && !value.startsWith("#"))
            .distinct().toList();
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("proxy node list must contain 1 to 500 nodes");
        }
        for (var node : nodes) validateNode(node);
        return Map.of("nodes", nodes);
    }

    private static void validateNode(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("proxy node URL is invalid", error);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!List.of("vless", "http", "https", "socks5", "socks5h").contains(scheme)) {
            throw new IllegalArgumentException("unsupported proxy node scheme");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("proxy node has no host");
    }

    private static int nodeCount(Map<String, Object> secret) {
        return secret.get("nodes") instanceof List<?> nodes ? nodes.size() : 0;
    }

    private static String name(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 100) {
            throw new IllegalArgumentException("proxy pool name must contain 1 to 100 characters");
        }
        return normalized;
    }

    private static String aad(UUID id, long revision) {
        return "proxy-pool:" + id + ":" + revision;
    }

    private enum Mode {
        SUBSCRIPTION_URL,
        NODE_LIST;

        static Mode parse(String value) {
            try {
                return value == null ? SUBSCRIPTION_URL : valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unsupported proxy pool mode", error);
            }
        }
    }

    private record PoolRow(
        UUID id, String name, Mode mode, boolean enabled, byte[] encryptedPayload,
        byte[] nonce, int keyVersion, long revision, int nodeCount,
        Instant createdAt, Instant updatedAt
    ) {}

    public record ProxyPoolView(
        UUID id, String name, String mode, boolean enabled, int nodeCount,
        boolean sourceConfigured, List<String> providerIds,
        Map<String, List<String>> bindingScopes,
        Instant createdAt, Instant updatedAt
    ) {}

    public record SaveCommand(
        String name, String mode, Boolean enabled, String source, List<String> providerIds,
        Map<String, List<String>> bindingScopes
    ) {}
}
