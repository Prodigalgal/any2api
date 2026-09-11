package com.any2api.provider.arena;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.util.Set;

/** Validates the small semantic subset that the Arena Web mapper can translate. */
final class ArenaRequestMapper {
    private static final Set<String> PROVIDER_OPTIONS = Set.of("mode", "model_id", "web_search");
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    void validate(CanonicalRequest request) {
        var unknown = request.providerOptions().keySet().stream()
            .filter(key -> !PROVIDER_OPTIONS.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw OpenAiRequestException.unknownProviderOption(
                "provider_options.arena." + unknown.getFirst(),
                "unsupported provider option for arena: " + unknown.getFirst());
        }
        var mode = request.providerOptions().get("mode");
        if (mode != null && (!(mode instanceof String)
            || !Set.of("direct", "direct-battle", "direct_battle")
                .contains(String.valueOf(mode).trim().toLowerCase()))) {
            throw OpenAiRequestException.invalid(
                "provider_options.arena.mode", "Arena only supports direct battle mode");
        }
        var modelId = request.providerOptions().get("model_id");
        if (modelId != null && (!(modelId instanceof String)
            || !MODEL_ID.matcher(String.valueOf(modelId).trim()).matches())) {
            throw OpenAiRequestException.invalid(
                "provider_options.arena.model_id", "Arena model_id must be a provider model UUID");
        }
        requireBoolean(request.providerOptions().get("web_search"),
            "provider_options.arena.web_search");
        if (!request.generation().isEmpty()) {
            throw OpenAiRequestException.unsupported(
                "generation", "Arena does not translate generation parameters");
        }
        if (!request.reasoning().isEmpty()) {
            throw OpenAiRequestException.unsupported(
                "reasoning", "Arena does not translate reasoning parameters");
        }
        if (!request.tools().isEmpty()) {
            throw OpenAiRequestException.unsupported("tools", "Arena does not support tools");
        }
        var raw = request.rawRequest();
        if (raw == null) return;
        requireBoolean(raw.path("web_search"), "web_search");
        rejectIfPresent(raw, "response_format");
        rejectIfPresent(raw, "stream_options");
        rejectIfPresent(raw, "text");
        rejectIfPresent(raw, "metadata");
        rejectIfPresent(raw, "parallel_tool_calls");
        var toolChoice = raw.path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()
            && (!toolChoice.isTextual() || !"none".equalsIgnoreCase(toolChoice.asText().trim()))) {
            throw OpenAiRequestException.unsupported(
                "tool_choice", "Arena only accepts tool_choice=none because tools are unsupported");
        }
    }

    private static void requireBoolean(Object value, String field) {
        if (value != null && !(value instanceof Boolean)) {
            throw OpenAiRequestException.invalid(field, field + " must be a boolean");
        }
    }

    private static void requireBoolean(tools.jackson.databind.JsonNode value, String field) {
        if (value != null && !value.isMissingNode() && !value.isNull() && !value.isBoolean()) {
            throw OpenAiRequestException.invalid(field, field + " must be a boolean");
        }
    }

    private static void rejectIfPresent(tools.jackson.databind.JsonNode raw, String field) {
        if (!raw.has(field) || raw.path(field).isNull()) return;
        throw OpenAiRequestException.unsupported(
            field, "Arena does not translate the " + field + " parameter");
    }
}
