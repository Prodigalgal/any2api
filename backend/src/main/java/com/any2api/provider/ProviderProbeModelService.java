package com.any2api.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Manages the basic model used once for each newly stored account.
 * Persisted in providers.config JSON and cached in memory.
 */
@Service
public class ProviderProbeModelService {
    private static final String CONFIG_KEY = "probe_model";
    private final JdbcClient jdbc;
    private final AtomicReference<Map<String, String>> configured =
        new AtomicReference<>(Map.of());

    public ProviderProbeModelService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> configuredProbeModel(String providerId) {
        var model = configured.get().get(providerId);
        return model != null && !model.isBlank() ? Optional.of(model) : Optional.empty();
    }

    public Optional<String> resolveProbeModel(String providerId, Optional<String> fallback) {
        var custom = configuredProbeModel(providerId);
        if (custom.isPresent()) {
            return custom;
        }
        return fallback;
    }

    public void set(String providerId, String probeModel) {
        var normalized = probeModel == null ? "" : probeModel.trim();
        if (!normalized.isBlank()) {
            if (AdvancedModelProbePolicy.isAdvancedModel(normalized)) {
                throw new IllegalArgumentException(
                    "Model " + normalized + " is an advanced/heavy model and cannot be used for probing");
            }
            var count = jdbc.sql("""
                    SELECT COUNT(*) FROM models
                    WHERE provider_id = :providerId AND upstream_id = :modelId
                    """)
                .param("providerId", providerId)
                .param("modelId", normalized)
                .query(Long.class)
                .single();
            if (count == 0) {
                throw new IllegalArgumentException(
                    "Model " + normalized + " does not exist under provider " + providerId);
            }
            jdbc.sql("""
                UPDATE providers
                SET config = jsonb_set(
                    COALESCE(config, '{}'::jsonb),
                    '{probe_model}',
                    to_jsonb(CAST(:probeModel AS text)),
                    TRUE),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :providerId AND installed = TRUE
                """)
                .param("probeModel", normalized)
                .param("providerId", providerId)
                .update();
            configured.updateAndGet(prev -> {
                var next = new LinkedHashMap<>(prev);
                next.put(providerId, normalized);
                return Map.copyOf(next);
            });
        } else {
            jdbc.sql("""
                UPDATE providers
                SET config = config - 'probe_model',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :providerId AND installed = TRUE
                """)
                .param("providerId", providerId)
                .update();
            configured.updateAndGet(prev -> {
                var next = new LinkedHashMap<>(prev);
                next.remove(providerId);
                return Map.copyOf(next);
            });
        }
    }

    @Scheduled(
        initialDelayString = "${any2api.providers.probe-model-initial-delay:0s}",
        fixedDelayString = "${any2api.providers.probe-model-refresh-interval:5s}"
    )
    public void refresh() {
        var values = jdbc.sql("""
            SELECT id, config->>:configKey AS probe_model
            FROM providers
            WHERE installed = TRUE
              AND config->>:configKey IS NOT NULL
            """)
            .param("configKey", CONFIG_KEY)
            .query((row, ignored) -> Map.entry(
                row.getString("id"), row.getString("probe_model")))
            .list();
        var next = new LinkedHashMap<String, String>();
        values.forEach(entry -> {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                next.put(entry.getKey(), entry.getValue().trim());
            }
        });
        configured.set(Map.copyOf(next));
    }
}
