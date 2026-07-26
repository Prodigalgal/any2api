package com.any2api.provider;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProviderCatalogSynchronizer implements ApplicationRunner {

    private final ProviderRegistry registry;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ProviderCatalogSynchronizer(
        ProviderRegistry registry,
        JdbcClient jdbc,
        TransactionTemplate transactions,
        ObjectMapper objectMapper
    ) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(ignored -> registry.list().forEach(this::synchronize));
    }

    private void synchronize(ProviderManifest manifest) {
        jdbc.sql("""
            INSERT INTO providers(id, display_name, enabled, adapter_version, request_schema_version)
            VALUES (:id, :displayName, TRUE, :adapterVersion, :schemaVersion)
            ON CONFLICT (id) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                adapter_version = EXCLUDED.adapter_version,
                request_schema_version = EXCLUDED.request_schema_version,
                updated_at = CURRENT_TIMESTAMP
            """)
            .param("id", manifest.id())
            .param("displayName", manifest.displayName())
            .param("adapterVersion", manifest.adapterVersion())
            .param("schemaVersion", manifest.requestSchemaVersion())
            .update();

        var capabilities = objectMapper.valueToTree(manifest.capabilities()).toString();
        for (var upstreamId : manifest.defaultModels()) {
            jdbc.sql("""
                INSERT INTO models(
                    id, provider_id, upstream_id, display_name, enabled,
                    capabilities, catalog_version, fetched_at)
                VALUES (
                    :id, :providerId, :upstreamId, :displayName, TRUE,
                    CAST(:capabilities AS jsonb), 1, CURRENT_TIMESTAMP)
                ON CONFLICT (provider_id, upstream_id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    capabilities = EXCLUDED.capabilities,
                    catalog_version = models.catalog_version + 1,
                    fetched_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """)
                .param("id", manifest.id() + "/" + upstreamId)
                .param("providerId", manifest.id())
                .param("upstreamId", upstreamId)
                .param("displayName", upstreamId)
                .param("capabilities", capabilities)
                .update();
        }
    }
}
