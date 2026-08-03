package com.any2api.auth;

import com.any2api.provider.ProviderRegistry;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ApiKeyRepository keys;
    private final ApiKeyGrantStore grantStore;
    private final ApiKeyAuthenticator authenticator;
    private final ProviderRegistry providers;
    private final JdbcClient jdbc;

    public ApiKeyService(
        ApiKeyRepository keys,
        ApiKeyGrantStore grantStore,
        ApiKeyAuthenticator authenticator,
        ProviderRegistry providers,
        JdbcClient jdbc
    ) {
        this.keys = keys;
        this.grantStore = grantStore;
        this.authenticator = authenticator;
        this.providers = providers;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        return keys.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @Transactional
    public Created create(CreateCommand command) {
        var name = normalizedName(command.name());
        var providerScopes = normalizedScopes(command.providerModels());
        var protocols = command.protocols() == null ? Set.<ApiKeyProtocol>of()
            : Set.copyOf(command.protocols());
        var features = command.features() == null ? Set.<ApiKeyFeature>of()
            : Set.copyOf(command.features());
        if (protocols.isEmpty()) {
            throw new IllegalArgumentException("at least one API key protocol is required");
        }
        if (command.expiresAt() != null && !command.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("API key expiry must be in the future");
        }
        var random = new byte[32];
        RANDOM.nextBytes(random);
        var secret = "sk-a2a-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var prefix = secret.substring(0, 15);
        var entity = ApiKeyEntity.create(
            name, prefix, ApiKeyAuthenticator.hash(secret),
            command.expiresAt());
        entity = keys.saveAndFlush(entity);
        grantStore.replace(entity.getId(), providerScopes, protocols, features);
        return new Created(view(entity), secret);
    }

    @Transactional
    public View setEnabled(UUID id, boolean enabled) {
        var key = require(id);
        key.setEnabled(enabled);
        key = keys.save(key);
        invalidateAfterCommit(key.getKeyHash());
        return view(key);
    }

    @Transactional
    public void delete(UUID id) {
        var key = require(id);
        keys.delete(key);
        invalidateAfterCommit(key.getKeyHash());
    }

    private ApiKeyEntity require(UUID id) {
        return keys.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("unknown API key: " + id));
    }

    private void invalidateAfterCommit(String keyHash) {
        Runnable invalidate = () -> authenticator.invalidate(keyHash).subscribe();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
    }

    private String normalizedName(String value) {
        var name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("API key name must contain between 1 and 120 characters");
        }
        return name;
    }

    private Map<String, ApiKeyProviderScope> normalizedScopes(Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("at least one API key provider is required");
        }
        var result = new LinkedHashMap<String, ApiKeyProviderScope>();
        raw.forEach((providerId, models) -> {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("API key provider id is required");
            }
            var provider = providers.requirePlugin(providerId.trim());
            var normalized = new ArrayList<String>();
            if (models != null) {
                models.stream().map(String::trim).filter(value -> !value.isBlank())
                    .distinct().forEach(normalized::add);
            }
            if (normalized.size() > 100) {
                throw new IllegalArgumentException(
                    "API key model scope must not exceed 100 models per provider");
            }
            for (var model : normalized) {
                var count = jdbc.sql("""
                    SELECT COUNT(*) FROM models
                    WHERE provider_id = :providerId AND upstream_id = :model AND enabled = TRUE
                    """)
                    .param("providerId", provider.manifest().id())
                    .param("model", model)
                    .query(Long.class)
                    .single();
                if (count == 0) {
                    throw new IllegalArgumentException(
                        "unknown enabled model for provider " + provider.manifest().id());
                }
            }
            var normalizedProviderId = provider.manifest().id();
            result.put(normalizedProviderId, normalized.isEmpty()
                ? ApiKeyProviderScope.allModels(normalizedProviderId)
                : ApiKeyProviderScope.selectedModels(normalizedProviderId, Set.copyOf(normalized)));
        });
        return Map.copyOf(result);
    }

    private View view(ApiKeyEntity key) {
        return View.from(key, grantStore.read(key));
    }

    public record CreateCommand(
        String name,
        Map<String, List<String>> providerModels,
        Set<ApiKeyProtocol> protocols,
        Set<ApiKeyFeature> features,
        Instant expiresAt
    ) {}

    public record Created(View key, String secret) {}

    public record View(
        UUID id,
        String name,
        String prefix,
        boolean enabled,
        Map<String, List<String>> providerModels,
        Set<ApiKeyProtocol> protocols,
        Set<ApiKeyFeature> features,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        static View from(ApiKeyEntity key, ApiKeyGrant grant) {
            return new View(
                key.getId(), key.getName(), key.getPrefix(), key.isEnabled(),
                grant.providerModels(), grant.protocols(), grant.features(), key.getLastUsedAt(),
                key.getExpiresAt(), key.getCreatedAt(), key.getUpdatedAt());
        }
    }
}
