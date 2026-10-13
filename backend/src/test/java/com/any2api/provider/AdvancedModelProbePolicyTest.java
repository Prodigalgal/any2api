package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdvancedModelProbePolicyTest {
    @Test
    void evaluatesTheUpstreamModelWithoutConfusingVendorNamesWithMaxModels() {
        for (var model : java.util.List.of("MiniMax-M3.1-Flash-Preview", "minmax/MiniMax-M3.1-Flash-Preview",
                "mimo/mimo-v2.6", "arena/gemini-3.5-flash-lite")) {
            assertThat(AdvancedModelProbePolicy.isAdvancedModel(model)).as(model).isFalse();
        }
        for (var model : java.util.List.of("mimo/mimo-v2.6-pro", "qwen/qwen3-max", "arena/claude-sonnet",
                "minmax/MiniMax-M3-thinking", "grok/grok-imagine-image", "deepseek/deepseek-r1")) {
            assertThat(AdvancedModelProbePolicy.isAdvancedModel(model)).as(model).isTrue();
        }
    }
}
