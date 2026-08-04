package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyScopeException;
import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ModelRuntimeGuard;
import com.any2api.provider.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class ModelsController {

    private final ProviderRegistry registry;
    private final ModelCatalogCache catalog;
    private final ApiKeyAuthorization authorization;
    private final ModelRuntimeGuard runtimeGuard;

    public ModelsController(
        ProviderRegistry registry,
        ModelCatalogCache catalog,
        ApiKeyAuthorization authorization,
        ModelRuntimeGuard runtimeGuard
    ) {
        this.registry = registry;
        this.catalog = catalog;
        this.authorization = authorization;
        this.runtimeGuard = runtimeGuard;
    }

    @GetMapping({"/v1/models", "/api/catalog/v1/models"})
    public Mono<Map<String, Object>> models(ServerWebExchange exchange) {
        var grant = authorization.current(exchange).orElse(ApiKeyGrant.unrestricted());
        var enabledProviders = registry.list().stream()
            .map(item -> item.id()).collect(Collectors.toUnmodifiableSet());
        return catalog.list().map(models -> Map.of(
            "object", "list",
            "data", models.stream()
                .filter(model -> enabledProviders.contains(model.providerId()))
                .filter(model -> grant.allowsModel(model.providerId(), model.id()))
                .map(model -> response(model, true))
                .toList()));
    }

    @GetMapping("/{providerId:[a-z][a-z0-9_-]{1,31}}/v1/models")
    public Mono<Map<String, Object>> providerModels(
        @PathVariable String providerId,
        ServerWebExchange exchange
    ) {
        registry.require(providerId);
        var grant = authorization.grant(exchange);
        if (!grant.allowsProvider(providerId)) {
            throw new ApiKeyScopeException("API key does not allow the requested provider");
        }
        return catalog.list().map(models -> Map.of(
            "object", "list",
            "data", models.stream()
                .filter(model -> model.providerId().equals(providerId))
                .filter(model -> grant.allowsModel(model.providerId(), model.id()))
                .map(model -> response(model, false))
                .toList()));
    }

    private Map<String, Object> response(ModelCatalogCache.Entry model, boolean namespaced) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", namespaced ? model.providerId() + "/" + model.id() : model.id());
        result.put("object", "model");
        result.put("created", model.created());
        result.put("owned_by", model.providerId());
        result.put("name", model.displayName());
        result.put("provider_name", model.providerName());
        result.put("cataloged", true);
        result.put("available", model.available()
            && runtimeGuard.callable(model.providerId(), model.id()));
        result.put("capabilities", model.capabilities());
        result.put("supported_parameters", model.capabilities().path("supported_parameters"));
        result.put("provider_options", model.capabilities().path("provider_options"));
        result.put("max_context_tokens", nullable(model.capabilities(), "max_context_tokens"));
        result.put("max_input_tokens", nullable(model.capabilities(), "max_input_tokens"));
        result.put("max_output_tokens", nullable(model.capabilities(), "max_output_tokens"));
        result.put("reasoning", model.capabilities().path("reasoning"));
        result.put("tools", model.capabilities().path("tools"));
        result.put("streaming", model.capabilities().path("streaming").asBoolean(false));
        result.put("multimodal", model.capabilities().path("multimodal"));
        result.put("catalog_source", model.catalogSource());
        result.put("metadata", model.metadata());
        result.put("random_roles", model.randomRoles());
        result.put("runtime", runtime(model));
        return result;
    }

    private Map<String, Object> runtime(ModelCatalogCache.Entry model) {
        var guard = runtimeGuard.snapshot(model.providerId(), model.id());
        var runtime = new LinkedHashMap<String, Object>();
        runtime.put("status", "OPEN".equals(guard.circuitState())
            || "FORCED_OPEN".equals(guard.circuitState())
            ? "UNAVAILABLE" : model.runtimeStatus());
        runtime.put("eligible_account_count", model.eligibleAccountCount());
        runtime.put("available_account_count", model.availableAccountCount());
        runtime.put("quota_limited_account_count", model.quotaLimitedAccountCount());
        runtime.put("rolling_request_count", model.rollingRequestCount());
        runtime.put("rolling_attempt_count", model.rollingAttemptCount());
        runtime.put("rolling_success_rate", model.rollingSuccessRate());
        runtime.put("p50_ms", model.p50Ms());
        runtime.put("p95_ms", model.p95Ms());
        runtime.put("last_attempt_at", model.lastAttemptAt());
        runtime.put("last_success_at", model.lastSuccessAt());
        runtime.put("probe_status", model.probeStatus());
        runtime.put("probe_error", model.probeError());
        runtime.put("probed_at", model.probedAt());
        runtime.put("concurrent", guard.concurrent());
        runtime.put("queue_depth", guard.queueDepth());
        runtime.put("circuit_state", guard.circuitState());
        runtime.put("bulkhead_rejections", guard.bulkheadRejections());
        runtime.put("circuit_rejections", guard.circuitRejections());
        return runtime;
    }

    private Object nullable(tools.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value;
    }

}
