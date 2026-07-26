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
    boolean configured
) {
}

