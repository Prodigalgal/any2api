package com.any2api.provider;

import java.util.regex.Pattern;

/**
 * Enforces the rule that high-end / flagship / heavy models (such as Max, Pro, Sonnet, Opus,
 * thinking models, reasoning models, video/image generators) must NEVER be probed.
 * Probes are strictly limited to single, lightweight text models per provider.
 */
public final class AdvancedModelProbePolicy {
    private static final Pattern ADVANCED_MODEL_PATTERN = Pattern.compile(
        "(?i).*(max|pro|sonnet|opus|o1|o3|thinking|reasoner|r1|ultra|flagship|-image|-video|-i2v|-t2v|-t2i|-edit|flux-|wan-|wan2|veo-|kling-|sora).*"
    );

    private AdvancedModelProbePolicy() {}

    /**
     * Determines whether a model is an advanced or heavy model that should never be probed.
     */
    public static boolean isAdvancedModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        var normalized = modelId.trim().substring(modelId.trim().lastIndexOf('/') + 1)
            .replaceFirst("(?i)^minimax-", "");
        return ADVANCED_MODEL_PATTERN.matcher(normalized).matches();
    }
}
