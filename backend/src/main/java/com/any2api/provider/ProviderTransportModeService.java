package com.any2api.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Resolves the inference transport without coupling request routing to a concrete provider.
 * The selected mode is persisted in the provider config JSON so switching modes does not
 * require an image rebuild. Lifecycle operations intentionally do not use this policy.
 */
@Service
public class ProviderTransportModeService {
    private static final String CONFIG_KEY = "inference_transport_mode";
    private static final Set<ProviderTransportMode> EXECUTION_MODES = Set.of(
        ProviderTransportMode.API, ProviderTransportMode.RUNTIME);

    private final JdbcClient jdbc;
    private final AtomicReference<Map<String, ProviderTransportMode>> configured =
        new AtomicReference<>(Map.of());

    public ProviderTransportModeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TransportPlan plan(InferenceProvider provider) {
        var supported = supported(provider);
        var requested = configured.get().getOrDefault(
            provider.manifest().id(), provider.defaultTransportMode());
        if (requested == ProviderTransportMode.AUTO) {
            var primary = supported.contains(ProviderTransportMode.API)
                ? ProviderTransportMode.API : ProviderTransportMode.RUNTIME;
            var fallback = primary == ProviderTransportMode.API
                && supported.contains(ProviderTransportMode.RUNTIME)
                ? ProviderTransportMode.RUNTIME : null;
            return new TransportPlan(requested, primary, fallback);
        }
        if (!supported.contains(requested)) {
            throw new IllegalStateException(
                "provider " + provider.manifest().id()
                    + " does not support transport mode " + requested);
        }
        return new TransportPlan(requested, requested, null);
    }

    public ProviderTransportMode set(
        InferenceProvider provider,
        ProviderTransportMode requested
    ) {
        if (requested == null) {
            throw new IllegalArgumentException("provider transport mode is required");
        }
        if (requested != ProviderTransportMode.AUTO
            && !supported(provider).contains(requested)) {
            throw new IllegalArgumentException(
                "provider " + provider.manifest().id()
                    + " does not support transport mode " + requested);
        }
        var updated = jdbc.sql("""
            UPDATE providers
            SET config = jsonb_set(
                COALESCE(config, '{}'::jsonb),
                '{inference_transport_mode}',
                to_jsonb(CAST(:mode AS text)),
                TRUE),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :providerId AND installed = TRUE
            """)
            .param("mode", requested.name())
            .param("providerId", provider.manifest().id())
            .update();
        if (updated != 1) {
            throw new IllegalArgumentException(
                "provider plugin is not installed: " + provider.manifest().id());
        }
        configured.updateAndGet(previous -> {
            var next = new LinkedHashMap<>(previous);
            next.put(provider.manifest().id(), requested);
            return Map.copyOf(next);
        });
        return requested;
    }

    public ModeView view(InferenceProvider provider) {
        var plan = plan(provider);
        return new ModeView(
            plan.requested(), plan.primary(), plan.fallback(), supported(provider));
    }

    @Scheduled(
        initialDelayString = "${any2api.providers.transport-mode-initial-delay:5s}",
        fixedDelayString = "${any2api.providers.transport-mode-refresh-interval:5s}"
    )
    public void refresh() {
        var values = jdbc.sql("""
            SELECT id, config->>:configKey AS mode
            FROM providers
            WHERE installed = TRUE
              AND config->>:configKey IS NOT NULL
            """)
            .param("configKey", CONFIG_KEY)
            .query((row, ignored) -> Map.entry(
                row.getString("id"), ProviderTransportMode.parse(row.getString("mode"))))
            .list();
        var next = new LinkedHashMap<String, ProviderTransportMode>();
        values.forEach(value -> next.put(value.getKey(), value.getValue()));
        configured.set(Map.copyOf(next));
    }

    private Set<ProviderTransportMode> supported(InferenceProvider provider) {
        var modes = provider.supportedTransportModes();
        if (modes == null || modes.isEmpty() || !EXECUTION_MODES.containsAll(modes)) {
            throw new IllegalStateException(
                "provider " + provider.manifest().id()
                    + " declares an invalid transport mode set");
        }
        if (!modes.contains(provider.defaultTransportMode())) {
            throw new IllegalStateException(
                "provider " + provider.manifest().id()
                    + " default transport mode is not supported");
        }
        return Set.copyOf(modes);
    }

    public record TransportPlan(
        ProviderTransportMode requested,
        ProviderTransportMode primary,
        ProviderTransportMode fallback
    ) {
        public boolean hasFallback() {
            return fallback != null;
        }
    }

    public record ModeView(
        ProviderTransportMode requested,
        ProviderTransportMode primary,
        ProviderTransportMode fallback,
        Set<ProviderTransportMode> supported
    ) {}
}
