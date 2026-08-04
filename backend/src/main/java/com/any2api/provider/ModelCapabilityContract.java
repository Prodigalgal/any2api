package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record ModelCapabilityContract(
    Map<String, List<String>> supportedParameters,
    Map<String, String> providerOptions,
    Long maxContextTokens,
    Long maxInputTokens,
    Long maxOutputTokens,
    boolean reasoning,
    List<String> reasoningLevels,
    ToolSupport tools,
    boolean streaming,
    MultimodalSupport multimodal
) {
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();
    private static final List<String> STANDARD_REASONING_LEVELS =
        List.of("auto", "none", "minimal", "low", "medium", "high");

    public ModelCapabilityContract {
        supportedParameters = supportedParameters == null ? Map.of()
            : supportedParameters.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
        reasoningLevels = reasoningLevels == null ? List.of() : List.copyOf(reasoningLevels);
        tools = tools == null ? new ToolSupport(false, List.of(), false) : tools;
        multimodal = multimodal == null
            ? new MultimodalSupport(List.of("text"), List.of("text")) : multimodal;
    }

    public static ModelCapabilityContract from(
        ProviderManifest manifest,
        ProviderProtocolContract protocol,
        DiscoveredModel model
    ) {
        var reasoning = supported(manifest, ProviderCapability.REASONING);
        var toolTypes = protocol.toolTypes().stream().sorted().toList();
        var input = new ArrayList<String>();
        input.add("text");
        addIf(input, "image", supported(manifest, ProviderCapability.IMAGE_INPUT));
        addIf(input, "audio", supported(manifest, ProviderCapability.AUDIO_INPUT));
        addIf(input, "video", supported(manifest, ProviderCapability.VIDEO_INPUT));
        addIf(input, "file", supported(manifest, ProviderCapability.FILE_INPUT));
        var output = new ArrayList<String>();
        output.add("text");
        addIf(output, "image", supported(manifest, ProviderCapability.IMAGE_GENERATION));
        addIf(output, "video", supported(manifest, ProviderCapability.VIDEO_GENERATION));

        var metadata = METADATA_MAPPER.valueToTree(model.metadata());
        var context = firstPositive(metadata,
            "max_context_tokens", "max_context_length", "context_length", "context_window");
        var inputLimit = firstPositive(metadata,
            "max_input_tokens", "input_token_limit", "max_prompt_tokens");
        var outputLimit = firstPositive(metadata,
            "max_output_tokens", "output_token_limit", "max_completion_tokens");
        return new ModelCapabilityContract(
            Map.of(
                "chat_completions", ProviderRequestValidation.acceptedParameters(
                    CanonicalRequest.Protocol.CHAT_COMPLETIONS, protocol),
                "responses", ProviderRequestValidation.acceptedParameters(
                    CanonicalRequest.Protocol.RESPONSES, protocol)),
            protocol.providerOptions().entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> entry.getValue().name().toLowerCase())),
            context, inputLimit, outputLimit, reasoning,
            reasoning ? STANDARD_REASONING_LEVELS : List.of(),
            new ToolSupport(!toolTypes.isEmpty(), toolTypes,
                protocol.chatParameters().contains("parallel_tool_calls")
                    || protocol.responsesParameters().contains("parallel_tool_calls")),
            supported(manifest, ProviderCapability.STREAMING),
            new MultimodalSupport(input, output));
    }

    public Map<String, Object> asMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("supported_parameters", supportedParameters);
        value.put("provider_options", providerOptions);
        value.put("max_context_tokens", maxContextTokens);
        value.put("max_input_tokens", maxInputTokens);
        value.put("max_output_tokens", maxOutputTokens);
        value.put("reasoning", Map.of(
            "supported", reasoning,
            "levels", reasoningLevels));
        value.put("tools", Map.of(
            "supported", tools.supported(),
            "types", tools.types(),
            "parallel", tools.parallel()));
        value.put("streaming", streaming);
        value.put("multimodal", Map.of(
            "input", multimodal.input(),
            "output", multimodal.output()));
        return value;
    }

    private static boolean supported(ProviderManifest manifest, ProviderCapability capability) {
        return manifest.capabilities().getOrDefault(capability, SupportLevel.UNSUPPORTED)
            != SupportLevel.UNSUPPORTED;
    }

    private static void addIf(List<String> values, String value, boolean condition) {
        if (condition) values.add(value);
    }

    private static Long firstPositive(JsonNode node, String... names) {
        for (var name : names) {
            var found = find(node, name, 0);
            if (found != null && found > 0) return found;
        }
        return null;
    }

    private static Long find(JsonNode node, String name, int depth) {
        if (node == null || depth > 4) return null;
        var direct = node.path(name);
        if (direct.isIntegralNumber()) return direct.asLong();
        if (direct.isTextual()) {
            try {
                return Long.parseLong(direct.asText().trim());
            } catch (NumberFormatException ignored) {
                // Continue looking for another official metadata occurrence.
            }
        }
        if (node.isObject()) {
            var properties = node.properties().iterator();
            while (properties.hasNext()) {
                var found = find(properties.next().getValue(), name, depth + 1);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var found = find(child, name, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    public record ToolSupport(boolean supported, List<String> types, boolean parallel) {
        public ToolSupport {
            types = types == null ? List.of() : List.copyOf(types);
        }
    }

    public record MultimodalSupport(List<String> input, List<String> output) {
        public MultimodalSupport {
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
        }
    }
}
