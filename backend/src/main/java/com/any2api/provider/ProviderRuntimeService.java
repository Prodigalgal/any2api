package com.any2api.provider;

import com.any2api.coordination.PostgresAdvisoryLocks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProviderRuntimeService {
    private final ProviderRegistry providers;
    private final ProviderInstallationCatalog installations;
    private final PostgresAdvisoryLocks locks;
    private final JdbcClient jdbc;

    public ProviderRuntimeService(
        ProviderRegistry providers,
        ProviderInstallationCatalog installations,
        PostgresAdvisoryLocks locks,
        JdbcClient jdbc
    ) {
        this.providers = providers;
        this.installations = installations;
        this.locks = locks;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ProviderRuntimeView> list() {
        return providers.plugins().stream()
            .map(plugin -> state(plugin.manifest()))
            .toList();
    }

    @Transactional
    public ProviderRuntimeView setEnabled(String providerId, boolean enabled) {
        var manifest = providers.requirePlugin(providerId).manifest();
        locks.lockTransaction("provider:" + providerId + ":runtime");
        var updated = jdbc.sql("""
            UPDATE providers SET enabled = :enabled, updated_at = CURRENT_TIMESTAMP
            WHERE id = :providerId AND installed = TRUE
            """)
            .param("enabled", enabled)
            .param("providerId", providerId)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("provider plugin is not installed: " + providerId);
        }
        if (!enabled) quarantine(providerId);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    installations.refresh();
                }
            });
        return state(manifest);
    }

    private void quarantine(String providerId) {
        jdbc.sql("""
            UPDATE accounts SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND enabled = TRUE
            """).param("providerId", providerId).update();
        jdbc.sql("""
            UPDATE scheduled_actions
            SET status = 'SUPERSEDED', lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'ProviderHotUnplugged', updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND status IN ('PENDING', 'LEASED')
            """).param("providerId", providerId).update();
        jdbc.sql("""
            UPDATE registration_jobs
            SET status = 'CANCELLED', cancel_requested = TRUE,
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'ProviderHotUnplugged', finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :providerId AND status IN ('PENDING', 'RUNNING')
            """).param("providerId", providerId).update();
    }

    private ProviderRuntimeView state(ProviderManifest manifest) {
        return jdbc.sql("""
            SELECT provider.enabled, provider.installed,
                   (SELECT COUNT(*) FROM accounts account
                    WHERE account.provider_id = provider.id) AS account_count,
                   (SELECT COUNT(*) FROM accounts account
                    WHERE account.provider_id = provider.id AND account.enabled = TRUE
                      AND account.status IN ('ACTIVE', 'DEGRADED')) AS enabled_account_count,
                   (SELECT COUNT(*) FROM models model
                    WHERE model.provider_id = provider.id AND model.enabled = TRUE) AS model_count
            FROM providers provider WHERE provider.id = :providerId
            """)
            .param("providerId", manifest.id())
            .query((row, ignored) -> map(row, manifest))
            .optional()
            .orElse(new ProviderRuntimeView(
                manifest.id(), manifest.displayName(), manifest.adapterVersion(),
                manifest.defaultModels(), manifest.capabilities(),
                false, false, 0, 0, 0));
    }

    private static ProviderRuntimeView map(ResultSet row, ProviderManifest manifest)
        throws SQLException {
        return new ProviderRuntimeView(
            manifest.id(), manifest.displayName(), manifest.adapterVersion(),
            manifest.defaultModels(), manifest.capabilities(),
            row.getBoolean("installed"), row.getBoolean("enabled"),
            row.getLong("account_count"), row.getLong("enabled_account_count"),
            row.getLong("model_count"));
    }

    public record ProviderRuntimeView(
        String id,
        String displayName,
        String adapterVersion,
        List<String> defaultModels,
        Map<ProviderCapability, SupportLevel> capabilities,
        boolean installed,
        boolean enabled,
        long accountCount,
        long enabledAccountCount,
        long modelCount
    ) {}
}
