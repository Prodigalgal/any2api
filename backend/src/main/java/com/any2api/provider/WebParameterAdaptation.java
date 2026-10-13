package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Describes adapter mappings, without treating a declared field as live proof of effectiveness. */
public final class WebParameterAdaptation {
    private static final List<String> GENERATION = List.of(
        "temperature", "top_p", "max_tokens", "max_completion_tokens", "max_output_tokens",
        "stop", "seed", "presence_penalty", "frequency_penalty", "logprobs", "top_logprobs");
    private static final Set<String> REASONING_FIELDS = Set.of("effort", "summary");

    private WebParameterAdaptation() { }

    public static List<String> compatibilityParameters() {
        return GENERATION;
    }

    /** Produces a provider-owned view; caller history and the original API request stay intact. */
    public static CanonicalRequest prepare(CanonicalRequest request, ProviderProtocolContract protocol,
                                           JsonNode modelCapabilities) {
        var supported = protocol.parameters(request.protocol());
        var raw = ((ObjectNode) request.rawRequest()).deepCopy();
        var generation = new LinkedHashMap<>(request.generation());
        for (var parameter : GENERATION) {
            var mode = protocol.parameterMappings().getOrDefault(parameter, Map.of()).get("mode");
            if (!supported.contains(parameter) || "best_effort_ignore".equals(mode)) {
                raw.remove(parameter);
                generation.remove(parameter);
            }
        }
        var reasoning = new LinkedHashMap<>(request.reasoning());
        if (!supported.contains("reasoning")
            && raw.path("reasoning").propertyNames().stream().allMatch(REASONING_FIELDS::contains)) {
            raw.remove("reasoning");
            reasoning.clear();
        }
        if (!supported.contains("reasoning_effort")) raw.remove("reasoning_effort");
        if (raw.path("n").asInt(0) == 1) raw.remove("n");
        var modalities = raw.path("modalities");
        if (modalities.isArray() && modalities.size() == 1 && "text".equals(modalities.get(0).asText())) {
            raw.remove("modalities");
        }
        clampOutputBudget(raw, generation, modelCapabilities);
        return new CanonicalRequest(request.requestId(), request.protocol(), request.providerId(), request.model(),
            request.stream(), request.messages(), Map.copyOf(generation), Map.copyOf(reasoning),
            request.tools(), request.providerOptions(), raw);
    }

    private static void clampOutputBudget(ObjectNode raw, Map<String, Object> generation, JsonNode capabilities) {
        if (capabilities == null) return;
        var output = capabilities.path("max_output_tokens");
        var context = capabilities.path("max_context_tokens");
        var ceiling = output.isIntegralNumber() && output.asLong() > 0 ? output.asLong() : Long.MAX_VALUE;
        if (context.isIntegralNumber() && context.asLong() > 0) {
            var remaining = context.asLong() - ModelRequestLimitGuard.estimateInputTokens(raw);
            // Actual input overflow is still rejected by the request limit guard.
            if (remaining > 0) ceiling = Math.min(ceiling, remaining);
        }
        for (var alias : List.of("max_output_tokens", "max_completion_tokens", "max_tokens")) {
            var requested = raw.path(alias);
            if (requested.isIntegralNumber() && requested.asLong() > ceiling) {
                raw.put(alias, ceiling);
                generation.put(alias, ceiling);
            }
        }
    }

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
                : ignored());
        }
        var reasoning = protocol.chatParameters().contains("reasoning")
            || protocol.responsesParameters().contains("reasoning");
        result.put("reasoning", reasoning
            ? protocol.parameterMappings().getOrDefault("reasoning", description("conditional", ""))
            : ignored());
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

    public static Map<String, Object> ignoredOutputLimit() {
        return Map.of("mode", "best_effort_ignore", "target", "", "evidence", "adapter_mapping",
            "reason", "web_has_no_output_limit_field", "enforced", false);
    }

    private static Map<String, Object> ignored() {
        return Map.of("mode", "best_effort_ignore", "target", "", "evidence", "adapter_mapping", "enforced", false);
    }

    private static Map<String, Object> description(String mode, String target) {
        return Map.of("mode", mode, "target", target, "evidence", "adapter_mapping");
    }
}
