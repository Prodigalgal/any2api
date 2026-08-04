package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCapabilityContractTest {
    @Test
    void combinesProviderProtocolAndOfficialModelMetadata() {
        var manifest = new ProviderManifest(
            "alpha", "Alpha", "test", "1", List.of("alpha-model"),
            Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.RESPONSES, SupportLevel.NATIVE,
                ProviderCapability.STREAMING, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE,
                ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
                ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE),
            true);
        var protocol = new ProviderProtocolContract(
            Map.of("thinking_budget", ProviderProtocolContract.OptionType.INTEGER),
            Set.of("temperature", "tools", "parallel_tool_calls", "reasoning"),
            Set.of("temperature", "tools", "parallel_tool_calls", "reasoning"),
            Set.of("function"));
        var model = new DiscoveredModel("alpha-model", "Alpha Model", Map.of(
            "meta", Map.of("max_context_length", 131072, "max_output_tokens", 8192)));

        var value = ModelCapabilityContract.from(manifest, protocol, model);

        assertThat(value.maxContextTokens()).isEqualTo(131072);
        assertThat(value.maxOutputTokens()).isEqualTo(8192);
        assertThat(value.streaming()).isTrue();
        assertThat(value.reasoningLevels()).contains("low", "medium", "high");
        assertThat(value.tools().types()).containsExactly("function");
        assertThat(value.multimodal().input()).containsExactly("text", "image");
        assertThat(value.supportedParameters().get("chat_completions"))
            .contains("model", "messages", "temperature", "tools");
        assertThat(value.providerOptions()).containsEntry("thinking_budget", "integer");
    }
}
