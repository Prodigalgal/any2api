package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WebParameterAdaptationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesProviderCharacterEvidenceWithoutInventingATokenContextLimit() {
        var result = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict(),
            Map.of("input_character_limit", 2621440)));
        assertThat(result.path("input").path("limit").asLong()).isEqualTo(2621440L);
        assertThat(result.path("input").path("unit").asText()).isEqualTo("provider_characters");
        assertThat(result.path("input").path("token_limit_verified").asBoolean()).isFalse();
        var unknown = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict()));
        assertThat(unknown.path("input").path("mode").asText()).isEqualTo("unknown");
        assertThat(unknown.path("input").has("limit")).isFalse();
    }

    @Test
    void distinguishesNativeTargetsFromConditionalAndUnsupportedControls() {
        var contract = new ProviderProtocolContract(Map.of(), Set.of("temperature", "top_p", "max_output_tokens"),
            Set.of("temperature", "top_p", "max_output_tokens"), Set.of("function"));
        var mimo = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(Map.of(
            "top_p", WebParameterAdaptation.mapped("modelConfig.topP"),
            "max_output_tokens", WebParameterAdaptation.nonBindingOutputLimit()))));
        assertThat(mimo.path("top_p").path("target").asText()).isEqualTo("modelConfig.topP");
        assertThat(mimo.path("max_output_tokens").path("mode").asText()).isEqualTo("non_binding_only");
        assertThat(mimo.path("max_output_tokens").path("enforced").asBoolean()).isFalse();
        assertThat(mimo.path("max_tokens").path("mode").asText()).isEqualTo("unsupported");
        var glm = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(Map.of(
            "max_output_tokens", WebParameterAdaptation.mapped("params.max_tokens")))));
        assertThat(glm.path("max_output_tokens").path("target").asText()).isEqualTo("params.max_tokens");
        var arena = mapper.valueToTree(WebParameterAdaptation.describe(ProviderProtocolContract.strict()
            .withParameterMappings(Map.of("max_output_tokens", WebParameterAdaptation.mapped("unaccepted")))));
        assertThat(arena.path("max_output_tokens").path("mode").asText()).isEqualTo("unsupported");
    }

    @Test
    void doesNotClaimExactReasoningLevelsForBooleanWebControls() {
        var contract = new ProviderProtocolContract(Map.of(), Set.of("reasoning"), Set.of("reasoning"), Set.of());
        for (var target : Set.of("thinking_enabled", "reason_enabled", "modelConfig.enableThinking", "model.variant")) {
            var result = mapper.valueToTree(WebParameterAdaptation.describe(contract.withParameterMappings(
                Map.of("reasoning", WebParameterAdaptation.toggle(target)))));
            assertThat(result.path("reasoning").path("mode").asText()).isEqualTo("toggle_mapping");
            assertThat(result.path("reasoning").path("exact_effort_levels").asBoolean()).isFalse();
        }
    }
}
