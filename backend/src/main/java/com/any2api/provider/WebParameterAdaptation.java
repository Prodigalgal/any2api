package com.any2api.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes adapter mappings, without treating a declared field as live proof of effectiveness. */
public final class WebParameterAdaptation {
    private static final List<String> GENERATION = List.of(
        "temperature", "top_p", "max_tokens", "max_completion_tokens", "max_output_tokens");

    private WebParameterAdaptation() { }

    public static Map<String, Object> describe(ProviderProtocolContract protocol) {
        return describe(protocol, Map.of());
    }

    public static Map<String, Object> describe(ProviderProtocolContract protocol, Map<String, Object> metadata) {
        var result = new LinkedHashMap<String, Object>();
        for (var parameter : GENERATION) {
            var accepted = protocol.chatParameters().contains(parameter)
                || protocol.responsesParameters().contains(parameter);
            result.put(parameter, accepted
                ? protocol.parameterMappings().getOrDefault(parameter, description("conditional", ""))
                : description("unsupported", ""));
        }
        var reasoning = protocol.chatParameters().contains("reasoning")
            || protocol.responsesParameters().contains("reasoning");
        result.put("reasoning", reasoning
            ? protocol.parameterMappings().getOrDefault("reasoning", description("conditional", ""))
            : description("unsupported", ""));
        result.put("function_tools", Map.of("mode", protocol.toolTypes().contains("function")
            ? "emulated" : "unsupported", "executor", "client", "evidence", "adapter_mapping"));
        result.put("search", protocol.parameterMappings().getOrDefault("search", description("unsupported", "")));
        result.put("responses_state", Map.of("mode", "gateway", "fields", List.of(
            "store", "previous_response_id")));
        var characterLimit = metadata.get("input_character_limit");
        result.put("input", characterLimit instanceof Number number && number.longValue() > 0
            ? Map.of("mode", "declared_character_limit", "limit", number.longValue(),
                "unit", "provider_characters", "evidence", "provider_model_metadata",
                "source_field", "input_character_limit", "token_limit_verified", false)
            : Map.of("mode", "unknown", "token_limit_verified", false));
        result.put("verification", "see_provider_parameter_report");
        return java.util.Collections.unmodifiableMap(result);
    }

    public static Map<String, Object> mapped(String target) {
        return description("mapped", target);
    }

    public static Map<String, Object> toggle(String target) {
        return Map.of("mode", "toggle_mapping", "target", target, "evidence", "adapter_mapping",
            "exact_effort_levels", false);
    }

    public static Map<String, Object> nonBindingOutputLimit() {
        return Map.of("mode", "non_binding_only", "target", "", "evidence", "adapter_mapping",
            "constraint", "at_or_above_configured_web_output_ceiling", "enforced", false,
            "ceiling_source", "deployment_configuration");
    }

    private static Map<String, Object> description(String mode, String target) {
        return Map.of("mode", mode, "target", target, "evidence", "adapter_mapping");
    }
}
