package com.any2api.provider;

import com.any2api.account.AccountSelectionService;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProviderCatalogSynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProviderCatalogSynchronizer.class);

    private final ProviderRegistry registry;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final AccountSelectionService accounts;
    private final ExecutorService databaseExecutor;

    public ProviderCatalogSynchronizer(
        ProviderRegistry registry,
        JdbcClient jdbc,
        TransactionTemplate transactions,
        ObjectMapper objectMapper,
        AccountSelectionService accounts,
        ExecutorService databaseExecutor
    ) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.accounts = accounts;
        this.databaseExecutor = databaseExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(ignored -> synchronizeInstalledProviders());
    }

    void synchronizeInstalledProviders() {
        jdbc.sql("""
            UPDATE providers
            SET installed = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE installed = TRUE
            """).update();
        registry.plugins().forEach(this::synchronizeManifest);
        retireRemovedProviderWork();
    }

    @Scheduled(
        initialDelayString = "${any2api.catalog.initial-delay:60s}",
        fixedDelayString = "${any2api.catalog.refresh-interval:15m}"
    )
    public void refreshOfficialCatalogs() {
        Flux.fromIterable(registry.enabledPlugins())
            .filter(provider -> provider.manifest().capabilities()
                .getOrDefault(ProviderCapability.MODEL_DISCOVERY, SupportLevel.UNSUPPORTED)
                != SupportLevel.UNSUPPORTED)
            .flatMap(this::discover, 2)
            .then()
            .block(Duration.ofMinutes(10));
    }

    private Mono<Void> discover(InferenceProvider provider) {
        var providerId = provider.manifest().id();
        return Flux.usingWhen(
                accounts.acquire(providerId),
                account -> provider.discoverModels(account)
                    .flatMap(models -> Mono.fromRunnable(() -> transactions.executeWithoutResult(
                        ignored -> synchronizeModels(provider.manifest(), models, "OFFICIAL")))
                        .subscribeOn(Schedulers.fromExecutor(databaseExecutor))),
                accounts::release,
                (account, ignored) -> accounts.release(account),
                accounts::release)
            .then()
            .onErrorResume(error -> {
                log.warn("Official model catalog refresh failed for provider {}: {}",
                    providerId, error.getMessage());
                return Mono.empty();
            });
    }

    private void synchronizeManifest(InferenceProvider provider) {
        var manifest = provider.manifest();
        jdbc.sql("""
            INSERT INTO providers(
                id, display_name, enabled, installed, adapter_version, request_schema_version)
            VALUES (:id, :displayName, TRUE, TRUE, :adapterVersion, :schemaVersion)
            ON CONFLICT (id) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                installed = TRUE,
                adapter_version = EXCLUDED.adapter_version,
                request_schema_version = EXCLUDED.request_schema_version,
                updated_at = CURRENT_TIMESTAMP
            """)
            .param("id", manifest.id())
            .param("displayName", manifest.displayName())
            .param("adapterVersion", manifest.adapterVersion())
            .param("schemaVersion", manifest.requestSchemaVersion())
            .update();

        var models = manifest.defaultModels().stream()
            .map(id -> new DiscoveredModel(id, id, Map.of("source", "manifest")))
            .toList();
        synchronizeModels(manifest, models, "MANIFEST");
    }

    private void retireRemovedProviderWork() {
        jdbc.sql("""
            UPDATE accounts account
            SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
            FROM providers provider
            WHERE account.provider_id = provider.id AND provider.installed = FALSE
              AND account.enabled = TRUE
            """).update();
        jdbc.sql("""
            UPDATE scheduled_actions action
            SET status = 'SUPERSEDED', lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'ProviderPluginRemoved', updated_at = CURRENT_TIMESTAMP
            FROM providers provider
            WHERE action.provider_id = provider.id AND provider.installed = FALSE
              AND action.status IN ('PENDING', 'LEASED')
            """).update();
        jdbc.sql("""
            UPDATE registration_jobs job
            SET status = 'CANCELLED', cancel_requested = TRUE,
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'ProviderPluginRemoved',
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            FROM providers provider
            WHERE job.provider_id = provider.id AND provider.installed = FALSE
              AND job.status IN ('PENDING', 'RUNNING')
            """).update();
    }

    private void synchronizeModels(
        ProviderManifest manifest,
        java.util.List<DiscoveredModel> discovered,
        String source
    ) {
        if (discovered.isEmpty()) {
            return;
        }
        if ("OFFICIAL".equals(source)) {
            jdbc.sql("""
                UPDATE models SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE provider_id = :providerId AND catalog_source = 'OFFICIAL'
                """)
                .param("providerId", manifest.id())
                .update();
        }
        var capabilities = objectMapper.valueToTree(manifest.capabilities()).toString();
        var rolesByModel = selectedRandomRoles(manifest, discovered);
        for (var model : discovered) {
            var metadata = objectMapper.valueToTree(model.metadata()).toString();
            var randomRoles = rolesByModel.getOrDefault(model.id(), Set.of()).stream()
                .map(RandomModelRole::catalogValue)
                .sorted()
                .toArray(String[]::new);
            jdbc.sql("""
                INSERT INTO models(
                    id, provider_id, upstream_id, display_name, enabled,
                    capabilities, catalog_version, fetched_at, catalog_source, metadata,
                    random_roles)
                VALUES (
                    :id, :providerId, :upstreamId, :displayName, TRUE,
                    CAST(:capabilities AS jsonb), 1, CURRENT_TIMESTAMP, :source,
                    CAST(:metadata AS jsonb), :randomRoles)
                ON CONFLICT (provider_id, upstream_id) DO UPDATE SET
                    display_name = CASE
                        WHEN models.catalog_source = 'OFFICIAL' AND :source = 'MANIFEST'
                            THEN models.display_name
                        ELSE EXCLUDED.display_name
                    END,
                    capabilities = EXCLUDED.capabilities,
                    catalog_source = CASE
                        WHEN models.catalog_source = 'OFFICIAL' AND :source = 'MANIFEST'
                            THEN models.catalog_source
                        ELSE EXCLUDED.catalog_source
                    END,
                    metadata = CASE
                        WHEN models.catalog_source = 'OFFICIAL' AND :source = 'MANIFEST'
                            THEN models.metadata
                        ELSE EXCLUDED.metadata
                    END,
                    random_roles = EXCLUDED.random_roles,
                    enabled = TRUE,
                    catalog_version = models.catalog_version + 1,
                    fetched_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """)
                .param("id", manifest.id() + "/" + model.id())
                .param("providerId", manifest.id())
                .param("upstreamId", model.id())
                .param("displayName", model.displayName())
                .param("capabilities", capabilities)
                .param("source", source)
                .param("metadata", metadata)
                .param("randomRoles", randomRoles)
                .update();
        }
    }

    private Map<String, Set<RandomModelRole>> selectedRandomRoles(
        ProviderManifest manifest,
        java.util.List<DiscoveredModel> discovered
    ) {
        var available = discovered.stream().map(DiscoveredModel::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var selected = new java.util.LinkedHashMap<String, Set<RandomModelRole>>();
        for (var entry : manifest.randomModelPreferences().entrySet()) {
            entry.getValue().stream().filter(available::contains).findFirst()
                .ifPresent(modelId -> selected.computeIfAbsent(
                    modelId, ignored -> new java.util.LinkedHashSet<>()).add(entry.getKey()));
        }
        return Map.copyOf(selected);
    }
}
