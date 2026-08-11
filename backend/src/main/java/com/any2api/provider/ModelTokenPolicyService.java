package com.any2api.provider;

import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

@Service
public final class ModelTokenPolicyService {
    private static final long MAX_CONFIGURABLE_TOKENS = 100_000_000L;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ExecutorService databaseExecutor;
    private final ModelCatalogCache catalog;

    public ModelTokenPolicyService(
        JdbcClient jdbc,
        PlatformTransactionManager transactionManager,
        ExecutorService databaseExecutor,
        ModelCatalogCache catalog
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.databaseExecutor = databaseExecutor;
        this.catalog = catalog;
    }

    public Mono<List<PolicyView>> list() {
        return catalog.list().map(entries -> entries.stream().map(this::view).toList());
    }

    public Mono<PolicyView> update(UpdateRequest request) {
        var providerId = clean(request.providerId(), "provider id");
        var modelId = clean(request.modelId(), "model id");
        return catalog.find(providerId, modelId).flatMap(optional -> {
            var current = optional.orElseThrow(() -> new IllegalArgumentException(
                "unknown enabled model: " + providerId + "/" + modelId));
            var normalized = normalize(request, current);
            return Mono.fromCallable(() -> Objects.requireNonNull(transactions.execute(ignored -> {
                    var updated = jdbc.sql("""
                        UPDATE models SET
                            max_context_tokens_override = :maxContext,
                            max_input_tokens_override = :maxInput,
                            max_output_tokens_override = :maxOutput,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE provider_id = :providerId AND upstream_id = :modelId
                        """)
                        .param("maxContext", normalized.maxContextTokens(), Types.BIGINT)
                        .param("maxInput", normalized.maxInputTokens(), Types.BIGINT)
                        .param("maxOutput", normalized.maxOutputTokens(), Types.BIGINT)
                        .param("providerId", providerId)
                        .param("modelId", modelId)
                        .update();
                    if (updated != 1) {
                        throw new IllegalArgumentException(
                            "unknown model: " + providerId + "/" + modelId);
                    }
                    return updated;
                })))
                .subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                .flatMap(ignored -> catalog.invalidate())
                .then(catalog.find(providerId, modelId))
                .map(refreshed -> view(refreshed.orElseThrow(() ->
                    new IllegalStateException("updated model disappeared from the catalog"))));
        });
    }

    private Limits normalize(UpdateRequest request, ModelCatalogCache.Entry current) {
        var discovered = limits(current.discoveredCapabilities());
        var overrides = new Limits(
            validateValue(request.maxContextTokens(), discovered.maxContextTokens(),
                "max context tokens"),
            validateValue(request.maxInputTokens(), discovered.maxInputTokens(),
                "max input tokens"),
            validateValue(request.maxOutputTokens(), discovered.maxOutputTokens(),
                "max output tokens"));
        var effective = effective(discovered, overrides);
        if (effective.maxContextTokens() != null) {
            requireWithinContext(effective.maxInputTokens(), effective.maxContextTokens(),
                "max input tokens");
            requireWithinContext(effective.maxOutputTokens(), effective.maxContextTokens(),
                "max output tokens");
        }
        return overrides;
    }

    private Long validateValue(Long value, Long discovered, String label) {
        if (value == null) return null;
        if (value <= 0 || value > MAX_CONFIGURABLE_TOKENS) {
            throw new IllegalArgumentException(
                label + " must be between 1 and " + MAX_CONFIGURABLE_TOKENS);
        }
        if (discovered != null && value > discovered) {
            throw new IllegalArgumentException(
                label + " cannot exceed the discovered provider limit " + discovered);
        }
        return value;
    }

    private void requireWithinContext(Long value, long context, String label) {
        if (value != null && value > context) {
            throw new IllegalArgumentException(label + " cannot exceed max context tokens");
        }
    }

    private PolicyView view(ModelCatalogCache.Entry entry) {
        var discovered = limits(entry.discoveredCapabilities());
        var overrides = new Limits(
            entry.maxContextTokensOverride(),
            entry.maxInputTokensOverride(),
            entry.maxOutputTokensOverride());
        return new PolicyView(
            entry.providerId(), entry.providerName(), entry.id(), entry.displayName(),
            entry.catalogSource(), discovered, overrides, effective(discovered, overrides));
    }

    private Limits limits(JsonNode capabilities) {
        return new Limits(
            positive(capabilities, "max_context_tokens"),
            positive(capabilities, "max_input_tokens"),
            positive(capabilities, "max_output_tokens"));
    }

    private Limits effective(Limits discovered, Limits overrides) {
        return new Limits(
            overrides.maxContextTokens() == null
                ? discovered.maxContextTokens() : overrides.maxContextTokens(),
            overrides.maxInputTokens() == null
                ? discovered.maxInputTokens() : overrides.maxInputTokens(),
            overrides.maxOutputTokens() == null
                ? discovered.maxOutputTokens() : overrides.maxOutputTokens());
    }

    private Long positive(JsonNode capabilities, String field) {
        if (capabilities == null) return null;
        var value = capabilities.path(field);
        return value.isIntegralNumber() && value.asLong() > 0 ? value.asLong() : null;
    }

    private String clean(String value, String label) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }

    public record UpdateRequest(
        String providerId,
        String modelId,
        Long maxContextTokens,
        Long maxInputTokens,
        Long maxOutputTokens
    ) {}

    public record Limits(
        Long maxContextTokens,
        Long maxInputTokens,
        Long maxOutputTokens
    ) {}

    public record PolicyView(
        String providerId,
        String providerName,
        String modelId,
        String displayName,
        String catalogSource,
        Limits discovered,
        Limits overrides,
        Limits effective
    ) {}
}
