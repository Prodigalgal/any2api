package com.any2api.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ApiKeyGrant(
    UUID keyId,
    String name,
    Map<String, ApiKeyProviderScope> providerScopes,
    Set<ApiKeyProtocol> protocols,
    Set<ApiKeyFeature> features,
    Instant expiresAt,
    boolean fullAccess
) {
    public ApiKeyGrant {
        var copied = new LinkedHashMap<String, ApiKeyProviderScope>();
        providerScopes.forEach((provider, scope) -> {
            if (!provider.equals(scope.providerId())) {
                throw new IllegalArgumentException("provider scope key must match provider id");
            }
            copied.put(provider, scope);
        });
        providerScopes = Map.copyOf(copied);
        protocols = Set.copyOf(protocols);
        features = Set.copyOf(features);
    }

    public static ApiKeyGrant unrestricted() {
        return new ApiKeyGrant(null, "system", Map.of(), Set.of(), Set.of(), null, true);
    }

    public boolean allowsProtocol(ApiKeyProtocol protocol) {
        return fullAccess || protocols.contains(protocol);
    }

    public boolean allowsProvider(String providerId) {
        return fullAccess || providerScopes.containsKey(providerId);
    }

    public boolean allowsFeature(ApiKeyFeature feature) {
        return fullAccess || features.contains(feature);
    }

    public boolean allowsModel(String providerId, String model) {
        if (fullAccess) return true;
        var scope = providerScopes.get(providerId);
        return scope != null && scope.allows(model);
    }

    public Map<String, List<String>> providerModels() {
        var result = new LinkedHashMap<String, List<String>>();
        providerScopes.forEach((provider, scope) -> result.put(
            provider,
            scope.allModels() ? List.of() : scope.models().stream().sorted().toList()));
        return Map.copyOf(result);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
