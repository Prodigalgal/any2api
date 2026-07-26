package com.any2api.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
    private final Map<String, InferenceProvider> providers;

    public ProviderRegistry(List<InferenceProvider> discoveredProviders) {
        providers = new LinkedHashMap<>();
        for (var provider : discoveredProviders) {
            var id = provider.manifest().id();
            if (!PROVIDER_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid provider id: " + id);
            }
            if (providers.putIfAbsent(id, provider) != null) {
                throw new IllegalArgumentException("duplicate provider id: " + id);
            }
        }
    }

    public List<ProviderManifest> list() {
        return providers.values().stream().map(InferenceProvider::manifest).toList();
    }

    public InferenceProvider require(String id) {
        var provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("unknown provider: " + id);
        }
        return provider;
    }
}
