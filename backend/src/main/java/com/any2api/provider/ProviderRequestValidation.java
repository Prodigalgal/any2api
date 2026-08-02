package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class ProviderRequestValidation {
    private static final Set<String> CHAT_PLATFORM_PARAMETERS = Set.of(
        "model", "messages", "stream", "provider_options", "response_format",
        "stream_options");
    private static final Set<String> RESPONSES_PLATFORM_PARAMETERS = Set.of(
        "model", "input", "instructions", "stream", "provider_options", "text",
        "metadata", "stream_options");
    private static final Set<String> TEXT_CONTENT_TYPES = Set.of(
        "text", "input_text", "output_text");

    private ProviderRequestValidation() {
    }

    public static void requireKnownOptions(CanonicalRequest request, Set<String> allowed) {
        var unknown = request.providerOptions().keySet().stream()
            .filter(key -> !allowed.contains(key))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            var option = unknown.getFirst();
            throw OpenAiRequestException.unknownProviderOption(
                "provider_options." + request.providerId() + "." + option,
                "unsupported provider option for " + request.providerId() + ": " + option);
        }
        rejectProviderOptionConflicts(request);
    }

    public static void requireKnownGenerationParameters(
        CanonicalRequest request,
        Set<String> supported
    ) {
        var unsupported = request.generation().keySet().stream()
            .filter(key -> !"stream_options".equals(key))
            .filter(key -> !supported.contains(key))
            .sorted()
            .toList();
        if (!unsupported.isEmpty()) {
            var parameter = unsupported.getFirst();
            throw OpenAiRequestException.unsupported(
                parameter,
                "unsupported standard parameter for " + request.providerId() + ": " + parameter);
        }
    }

    public static void requireBooleanParameters(CanonicalRequest request, String... fields) {
        requireParameterTypes(request, JsonNode::isBoolean, "boolean", fields);
    }

    public static void requireStringParameters(CanonicalRequest request, String... fields) {
        requireParameterTypes(request, JsonNode::isTextual, "string", fields);
    }

    public static void requirePositiveIntegerParameters(
        CanonicalRequest request,
        String... fields
    ) {
        requireParameterTypes(request,
            value -> value.isIntegralNumber() && value.asLong() > 0,
            "positive integer", fields);
    }

    public static void requireEnumParameter(
        CanonicalRequest request,
        String field,
        Set<String> values
    ) {
        var value = request.rawRequest().path(field);
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isTextual() || values.stream().noneMatch(
            candidate -> candidate.equalsIgnoreCase(value.asText()))) {
            throw OpenAiRequestException.invalid(
                field, field + " must be one of: " + String.join(", ", values));
        }
    }

    public static void requireProviderOptionEnum(
        CanonicalRequest request,
        String field,
        Set<String> values
    ) {
        var option = request.providerOptions().get(field);
        if (option == null) return;
        var value = String.valueOf(option);
        if (values.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(value))) {
            throw OpenAiRequestException.invalid(
                "provider_options." + request.providerId() + "." + field,
                field + " must be one of: " + String.join(", ", values));
        }
    }

    public static void requirePositiveIntegerProviderOption(
        CanonicalRequest request,
        String field
    ) {
        var option = request.providerOptions().get(field);
        if (option instanceof Number number && number.longValue() > 0) return;
        if (option != null) {
            throw OpenAiRequestException.invalid(
                "provider_options." + request.providerId() + "." + field,
                field + " must be a positive integer");
        }
    }

    public static void requireConsistentBooleanAliases(
        CanonicalRequest request,
        String providerOption,
        String... aliases
    ) {
        var values = new HashSet<Boolean>();
        var option = request.providerOptions().get(providerOption);
        if (option instanceof Boolean booleanValue) values.add(booleanValue);
        for (var alias : aliases) {
            var value = request.rawRequest().path(alias);
            if (value.isBoolean()) values.add(value.asBoolean());
        }
        if (values.size() > 1) {
            throw OpenAiRequestException.conflict(
                providerOption,
                "conflicting boolean aliases for " + providerOption + ": "
                    + String.join(", ", aliases));
        }
    }

    public static void requireReasoningBooleanConsistency(
        CanonicalRequest request,
        String providerOption,
        Set<String> disabledEfforts,
        String... rawAliases
    ) {
        Boolean explicit = request.providerOptions().get(providerOption) instanceof Boolean value
            ? value : null;
        for (var alias : rawAliases) {
            var value = request.rawRequest().path(alias);
            if (value.isBoolean()) explicit = value.asBoolean();
        }
        if (explicit == null) return;
        var effort = String.valueOf(request.reasoning().getOrDefault(
            "effort", request.rawRequest().path("reasoning_effort").asText("")))
            .trim().toLowerCase();
        if (effort.isBlank() || "auto".equals(effort)) return;
        var reasoningEnabled = !disabledEfforts.contains(effort);
        if (explicit != reasoningEnabled) {
            throw OpenAiRequestException.conflict(
                providerOption,
                providerOption + " conflicts with reasoning effort " + effort);
        }
    }

    private static void requireParameterTypes(
        CanonicalRequest request,
        java.util.function.Predicate<JsonNode> accepted,
        String expected,
        String... fields
    ) {
        for (var field : fields) {
            var value = request.rawRequest().path(field);
            if (!value.isMissingNode() && !value.isNull() && !accepted.test(value)) {
                throw OpenAiRequestException.invalid(
                    field, field + " must be a " + expected);
            }
        }
    }

    public static void requireSupportedContent(
        CanonicalRequest request,
        ProviderManifest manifest
    ) {
        for (var message : request.messages()) {
            var content = message.path("content");
            if (!content.isArray()) continue;
            for (var part : content) {
                if (part.isTextual()) continue;
                if (!part.isObject()) {
                    throw OpenAiRequestException.invalid(
                        "input", "message content blocks must be strings or objects");
                }
                var type = part.path("type").asText("");
                if (TEXT_CONTENT_TYPES.contains(type)) {
                    if (!part.path("text").isTextual()) {
                        throw OpenAiRequestException.invalid(
                            "input", "text content blocks require a string text field");
                    }
                    continue;
                }
                var capability = switch (type) {
                    case "image_url", "input_image" -> ProviderCapability.IMAGE_INPUT;
                    case "input_audio" -> ProviderCapability.AUDIO_INPUT;
                    case "video_url", "input_video" -> ProviderCapability.VIDEO_INPUT;
                    case "file", "input_file" -> ProviderCapability.FILE_INPUT;
                    default -> null;
                };
                if (capability == null) {
                    throw OpenAiRequestException.unsupported(
                        "input", "unsupported content block type: " + type);
                }
                if (capability != null && manifest.capabilities()
                    .getOrDefault(capability, SupportLevel.UNSUPPORTED)
                    == SupportLevel.UNSUPPORTED) {
                    throw OpenAiRequestException.unsupported(
                        "input", manifest.id() + " does not support content block type " + type);
                }
                if (capability == ProviderCapability.IMAGE_INPUT) {
                    var image = part.path("image_url");
                    var url = image.isTextual() ? image.asText("")
                        : image.path("url").asText("");
                    if (url.isBlank()) {
                        throw OpenAiRequestException.invalid(
                            "input", type + " content blocks require image_url");
                    }
                }
            }
        }
    }

    public static void requireSupportedRequest(
        CanonicalRequest request,
        ProviderManifest manifest
    ) {
        requireSupportedCapabilities(request, manifest);
    }

    public static void requireSupportedRequest(
        CanonicalRequest request,
        ProviderManifest manifest,
        ProviderProtocolContract contract
    ) {
        requireKnownOptions(request, contract.providerOptions().keySet());
        requireOptionTypes(request, contract);
        requireSupportedParameters(request, contract);
        requireSupportedTools(request, manifest, contract);
        requireSupportedReasoning(request, contract);
        requireSupportedCapabilities(request, manifest);
    }

    private static void requireSupportedCapabilities(
        CanonicalRequest request,
        ProviderManifest manifest
    ) {
        var protocolCapability = request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ProviderCapability.CHAT_COMPLETIONS : ProviderCapability.RESPONSES;
        requireCapability(manifest, protocolCapability, "protocol " + request.protocol());
        if (request.stream()) {
            requireCapability(manifest, ProviderCapability.STREAMING, "streaming");
        }
        if (request.rawRequest().hasNonNull("reasoning")
            || request.rawRequest().hasNonNull("reasoning_effort")) {
            requireCapability(manifest, ProviderCapability.REASONING, "reasoning");
        }
        requireSupportedContent(request, manifest);
        validateStreamOptions(request);

        var hasFunctionTools = request.tools().stream().anyMatch(tool ->
            "function".equals(tool.path("type").asText("function")));
        if (hasFunctionTools) {
            requireCapability(manifest, ProviderCapability.FUNCTION_TOOLS, "function tools");
        }
        var toolChoice = request.rawRequest().path("tool_choice");
        var toolRequired = toolChoice.isObject()
            || toolChoice.isTextual()
                && Set.of("required", "any").contains(toolChoice.asText().toLowerCase());
        if (toolRequired && request.tools().isEmpty()) {
            throw OpenAiRequestException.invalid(
                "tool_choice", "tool_choice requires at least one declared tool");
        }
        if (request.protocol() == CanonicalRequest.Protocol.RESPONSES) {
            var raw = request.rawRequest();
            if (raw.path("store").asBoolean(false)
                || !raw.path("previous_response_id").asText("").isBlank()
                || raw.hasNonNull("conversation")) {
                requireCapability(manifest, ProviderCapability.STORED_RESPONSES,
                    "stored Responses state");
            }
            validateResponsesText(raw.path("text"));
            var format = raw.path("text").path("format").path("type").asText("");
            if (Set.of("json_object", "json_schema").contains(format)) {
                requireCapability(manifest, ProviderCapability.STRUCTURED_OUTPUT,
                    "structured output");
            }
        } else {
            var responseFormat = request.rawRequest().path("response_format");
            validateFormat(responseFormat, "response_format");
            var format = responseFormat.path("type").asText("");
            if (Set.of("json_object", "json_schema").contains(format)) {
                requireCapability(manifest, ProviderCapability.STRUCTURED_OUTPUT,
                    "structured output");
            }
        }
        if (request.messages().isEmpty()) {
            throw OpenAiRequestException.invalid("input", "request input is required");
        }
        validateMessages(request.messages());
    }

    private static void requireSupportedParameters(
        CanonicalRequest request,
        ProviderProtocolContract contract
    ) {
        var supported = new HashSet<>(request.protocol()
            == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? CHAT_PLATFORM_PARAMETERS : RESPONSES_PLATFORM_PARAMETERS);
        supported.addAll(contract.parameters(request.protocol()));
        var unsupported = new java.util.ArrayList<String>();
        request.rawRequest().propertyNames().forEach(field -> {
            if (!supported.contains(field)) unsupported.add(field);
        });
        if (!unsupported.isEmpty()) {
            unsupported.sort(String::compareTo);
            var parameter = unsupported.getFirst();
            throw OpenAiRequestException.unsupported(
                parameter,
                "parameter is not translated by provider " + request.providerId()
                    + ": " + parameter);
        }
    }

    private static void requireOptionTypes(
        CanonicalRequest request,
        ProviderProtocolContract contract
    ) {
        var raw = request.rawRequest().path("provider_options").path(request.providerId());
        if (!raw.isObject()) return;
        for (var option : contract.providerOptions().entrySet()) {
            var value = raw.path(option.getKey());
            if (!value.isMissingNode() && !value.isNull() && !option.getValue().accepts(value)) {
                var parameter = "provider_options." + request.providerId() + "." + option.getKey();
                throw OpenAiRequestException.invalid(
                    parameter, parameter + " must be " + option.getValue().name().toLowerCase());
            }
        }
    }

    private static void requireSupportedReasoning(
        CanonicalRequest request,
        ProviderProtocolContract contract
    ) {
        var reasoning = request.rawRequest().path("reasoning");
        if (!reasoning.isObject()) return;
        reasoning.propertyNames().forEach(field -> {
            if (!contract.reasoningParameters().contains(field)) {
                throw OpenAiRequestException.unsupported(
                    "reasoning." + field,
                    "reasoning field is not translated by provider "
                        + request.providerId() + ": " + field);
            }
            if (Set.of("effort", "summary").contains(field)
                && !reasoning.path(field).isTextual()) {
                throw OpenAiRequestException.invalid(
                    "reasoning." + field, "reasoning." + field + " must be a string");
            }
        });
    }

    private static void requireSupportedTools(
        CanonicalRequest request,
        ProviderManifest manifest,
        ProviderProtocolContract contract
    ) {
        for (var tool : request.tools()) {
            if (!tool.isObject()) {
                throw OpenAiRequestException.invalid("tools", "tool definitions must be objects");
            }
            var type = tool.path("type").asText("function");
            if (!contract.toolTypes().contains(type)) {
                throw OpenAiRequestException.unsupported(
                    "tools", manifest.id() + " does not translate tool type " + type);
            }
            if ("function".equals(type)) {
                var definition = tool.path("function").isObject()
                    ? tool.path("function") : tool;
                if (definition.path("name").asText("").isBlank()) {
                    throw OpenAiRequestException.invalid(
                        "tools", "function tools require a name");
                }
                if (definition.hasNonNull("parameters")
                    && !definition.path("parameters").isObject()) {
                    throw OpenAiRequestException.invalid(
                        "tools", "function tool parameters must be a JSON Schema object");
                }
            }
        }
    }

    private static void validateStreamOptions(CanonicalRequest request) {
        var options = request.rawRequest().path("stream_options");
        if (!options.isObject()) return;
        if (!request.stream()) {
            throw OpenAiRequestException.invalid(
                "stream_options", "stream_options requires stream=true");
        }
        var allowed = request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? Set.of("include_usage") : Set.of("include_obfuscation");
        options.propertyNames().forEach(field -> {
            if (!allowed.contains(field)) {
                throw OpenAiRequestException.unsupported(
                    "stream_options." + field, "unsupported stream option: " + field);
            }
            if (!options.path(field).isBoolean()) {
                throw OpenAiRequestException.invalid(
                    "stream_options." + field,
                    "stream_options." + field + " must be a boolean");
            }
        });
        if (request.protocol() == CanonicalRequest.Protocol.RESPONSES
            && options.path("include_obfuscation").asBoolean(false)) {
            throw OpenAiRequestException.unsupported(
                "stream_options.include_obfuscation",
                "Responses stream obfuscation is not supported");
        }
    }

    private static void validateResponsesText(JsonNode text) {
        if (text.isMissingNode() || text.isNull()) return;
        text.propertyNames().forEach(field -> {
            if (!"format".equals(field)) {
                throw OpenAiRequestException.unsupported(
                    "text." + field, "Responses text option is not translated: " + field);
            }
        });
        validateFormat(text.path("format"), "text.format");
    }

    private static void validateFormat(JsonNode format, String parameter) {
        if (format.isMissingNode() || format.isNull()) return;
        var type = format.path("type").asText("");
        if (!Set.of("text", "json_object", "json_schema").contains(type)) {
            throw OpenAiRequestException.invalid(
                parameter + ".type",
                parameter + ".type must be text, json_object, or json_schema");
        }
    }

    private static void validateMessages(List<JsonNode> messages) {
        for (var message : messages) {
            if (!message.isObject()) {
                throw OpenAiRequestException.invalid("input", "messages must contain objects");
            }
            var role = message.path("role").asText("").toLowerCase();
            if (!Set.of("developer", "system", "user", "assistant", "tool").contains(role)) {
                throw OpenAiRequestException.invalid("input", "unsupported message role: " + role);
            }
            if (!message.has("content") && !message.path("tool_calls").isArray()) {
                throw OpenAiRequestException.invalid(
                    "input", "each message requires content or tool_calls");
            }
            var content = message.path("content");
            if (!content.isMissingNode() && !content.isNull()
                && !content.isTextual() && !content.isArray()) {
                throw OpenAiRequestException.invalid(
                    "input", "message content must be a string, array, or null");
            }
            if ("tool".equals(role)
                && message.path("tool_call_id").asText("").isBlank()) {
                throw OpenAiRequestException.invalid(
                    "input.tool_call_id", "tool messages require tool_call_id");
            }
            var calls = message.path("tool_calls");
            if (!calls.isMissingNode() && !calls.isArray()) {
                throw OpenAiRequestException.invalid(
                    "input.tool_calls", "tool_calls must be an array");
            }
            for (var call : calls) {
                var function = call.path("function");
                if (!call.isObject() || call.path("id").asText("").isBlank()
                    || !function.isObject()
                    || function.path("name").asText("").isBlank()
                    || !function.path("arguments").isTextual()) {
                    throw OpenAiRequestException.invalid(
                        "input.tool_calls", "assistant tool calls require id, name, and string arguments");
                }
            }
        }
    }

    private static void rejectProviderOptionConflicts(CanonicalRequest request) {
        var rawOptions = request.rawRequest().path("provider_options").path(request.providerId());
        if (!rawOptions.isObject()) return;
        for (var option : request.providerOptions().keySet()) {
            var topLevel = request.rawRequest().path(option);
            if (!topLevel.isMissingNode() && !topLevel.isNull()
                && !topLevel.equals(rawOptions.path(option))) {
                throw OpenAiRequestException.conflict(
                    option,
                    "provider option conflicts with top-level parameter: " + option);
            }
        }
    }

    private static void requireCapability(
        ProviderManifest manifest,
        ProviderCapability capability,
        String feature
    ) {
        if (manifest.capabilities().getOrDefault(capability, SupportLevel.UNSUPPORTED)
            == SupportLevel.UNSUPPORTED) {
            throw OpenAiRequestException.unsupported(
                feature, manifest.id() + " does not support " + feature);
        }
    }
}
