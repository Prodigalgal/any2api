package com.any2api.provider.grok_console;

import java.util.List;
import java.util.Map;

final class GrokConsoleModelCatalog {
    private static final List<ModelSpec> MODELS = List.of(
        new ModelSpec("grok-4.3", true, "medium", 1_000_000),
        new ModelSpec("grok-4.20-0309", false, "", 1_000_000),
        new ModelSpec("grok-4.20-0309-reasoning", true, "", 1_000_000),
        new ModelSpec("grok-4.20-0309-non-reasoning", false, "", 1_000_000),
        new ModelSpec("grok-4.20-multi-agent-0309", true, "medium", 2_000_000),
        new ModelSpec("grok-build-0.1", false, "", 256_000)
    );
    private static final Map<String, ModelSpec> BY_ID = MODELS.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(ModelSpec::id, item -> item));

    private GrokConsoleModelCatalog() {}

    static List<String> modelIds() {
        return MODELS.stream().map(ModelSpec::id).toList();
    }

    static ModelSpec require(String id) {
        var model = BY_ID.get(id);
        if (model == null) throw new IllegalArgumentException("unknown Grok Console model: " + id);
        return model;
    }

    record ModelSpec(String id, boolean reasoning, String defaultEffort, int maxOutputTokens) {}
}
