package com.any2api.provider;

import java.util.List;
import java.util.Map;

public record ProviderManifest(
    String id,
    String displayName,
    String adapterVersion,
    String requestSchemaVersion,
    List<String> defaultModels,
    Map<ProviderCapability, SupportLevel> capabilities,
    Map<RandomModelRole, List<String>> randomModelPreferences,
    boolean configured
) {
    public ProviderManifest {
        defaultModels = defaultModels == null ? List.of() : List.copyOf(defaultModels);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        randomModelPreferences = randomModelPreferences == null
            ? Map.of() : randomModelPreferences.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public ProviderManifest(
        String id,
        String displayName,
        String adapterVersion,
        String requestSchemaVersion,
        List<String> defaultModels,
        Map<ProviderCapability, SupportLevel> capabilities,
        boolean configured
    ) {
        this(id, displayName, adapterVersion, requestSchemaVersion, defaultModels,
            capabilities, Map.of(), configured);
    }
}
