package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyScopeException;
import com.any2api.provider.ModelCatalogCache;
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

    public ModelsController(
        ProviderRegistry registry,
        ModelCatalogCache catalog,
        ApiKeyAuthorization authorization
    ) {
        this.registry = registry;
        this.catalog = catalog;
        this.authorization = authorization;
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
        result.put("available", model.available());
        result.put("capabilities", model.capabilities());
        result.put("catalog_source", model.catalogSource());
        result.put("metadata", model.metadata());
        result.put("random_roles", model.randomRoles());
        return result;
    }

}
