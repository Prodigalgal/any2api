package com.any2api.protocol;

import com.any2api.routing.ResolvedRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CanonicalRequestParser {

    private static final List<String> GENERATION_FIELDS = List.of(
        "temperature", "top_p", "max_tokens", "max_completion_tokens", "max_output_tokens",
        "stop", "seed", "presence_penalty", "frequency_penalty", "parallel_tool_calls",
        "tool_choice", "stream_options");
    private static final List<String> CHAT_ACCEPTED_PARAMETERS = List.of(
        "frequency_penalty", "logprobs", "max_completion_tokens", "max_output_tokens",
        "max_tokens", "messages", "metadata", "modalities", "model", "n",
        "parallel_tool_calls", "presence_penalty", "provider_options", "reasoning",
        "reasoning_effort", "response_format", "seed", "stop", "stream",
        "stream_options", "temperature", "tool_choice", "tools", "top_logprobs",
        "top_p", "user", "store", "prompt_cache_key", "safety_identifier", "service_tier");
    private static final List<String> RESPONSES_ACCEPTED_PARAMETERS = List.of(
        "background", "include", "input", "instructions", "max_output_tokens",
        "max_tool_calls", "metadata", "model", "parallel_tool_calls",
        "previous_response_id", "prompt_cache_key", "provider_options", "reasoning",
        "reasoning_effort", "safety_identifier", "service_tier", "store", "stream",
        "stream_options", "temperature", "text", "tool_choice", "tools", "top_p",
        "truncation", "user", "client_metadata");

    private final ObjectMapper objectMapper;

    public CanonicalRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static List<String> acceptedParameters(CanonicalRequest.Protocol protocol) {
        return protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? CHAT_ACCEPTED_PARAMETERS : RESPONSES_ACCEPTED_PARAMETERS;
    }

    public CanonicalRequest parse(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw
    ) {
        return parse(protocol, route, raw, false, UUID.randomUUID().toString());
    }

    public CanonicalRequest parse(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw,
        String requestId
    ) {
        return parse(protocol, route, raw, false, requestId);
    }

    public CanonicalRequest parseCandidate(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw
    ) {
        return parse(protocol, route, raw, true, UUID.randomUUID().toString());
    }

    public CanonicalRequest parseCandidate(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw,
        String requestId
    ) {
        return parse(protocol, route, raw, true, requestId);
    }

    private CanonicalRequest parse(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw,
        boolean allowForeignProviderOptions,
        String requestId
    ) {
        validateShape(protocol, raw);
        rejectLimitConflicts(raw);
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        var stream = raw.path("stream").asBoolean(false);
        var messages = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? elements(raw.path("messages"))
            : responseMessages(raw.path("input"));
        if (protocol == CanonicalRequest.Protocol.RESPONSES && raw.has("instructions")
            && !raw.path("instructions").isNull()) {
            if (!raw.path("instructions").isTextual()) {
                throw OpenAiRequestException.invalid(
                    "instructions", "Responses instructions must be a string");
            }
            var withInstructions = new ArrayList<JsonNode>(messages.size() + 1);
            withInstructions.add(message("system", raw.path("instructions").deepCopy()));
            withInstructions.addAll(messages);
            messages = List.copyOf(withInstructions);
        }
        var generation = new LinkedHashMap<String, Object>();
        for (var field : GENERATION_FIELDS) {
            if (raw.has(field) && !raw.path(field).isNull()) {
                generation.put(field, objectMapper.convertValue(raw.path(field), Object.class));
            }
        }
        var reasoning = raw.path("reasoning").isObject()
            ? objectMapper.convertValue(raw.path("reasoning"), new TypeReference<Map<String, Object>>() {})
            : Map.<String, Object>of();
        var tools = OpenAiToolBridge.functions(raw.path("tools"));
        if (protocol == CanonicalRequest.Protocol.RESPONSES && raw.path("text").hasNonNull("verbosity")) {
            var verbosity = raw.path("text").path("verbosity").asText();
            if (!Set.of("low", "medium", "high").contains(verbosity)) {
                throw OpenAiRequestException.invalid("text.verbosity", "verbosity must be low, medium or high");
            }
            var steered = new ArrayList<JsonNode>();
            steered.add(message("system", objectMapper.getNodeFactory().textNode(
                "Requested answer detail level: " + verbosity + ".")));
            steered.addAll(messages);
            messages = List.copyOf(steered);
        }
        var providerOptions = providerOptions(
            raw, route.providerId(), allowForeignProviderOptions);
        return new CanonicalRequest(
            requestId,
            protocol,
            route.providerId(),
            route.upstreamModel(),
            stream,
            messages,
            Map.copyOf(generation),
            Map.copyOf(reasoning),
            tools,
            providerOptions,
            raw.deepCopy());
    }

    private List<JsonNode> elements(JsonNode value) {
        if (value instanceof ArrayNode array) {
            return objectMapper.convertValue(array, new TypeReference<List<JsonNode>>() {});
        }
        return value.isMissingNode() || value.isNull() ? List.of() : List.of(value.deepCopy());
    }

    private List<JsonNode> responseMessages(JsonNode input) {
        if (input.isMissingNode() || input.isNull()) return List.of();
        if (input.isTextual()) return List.of(message("user", input.deepCopy()));
        var items = input.isArray() ? input : objectMapper.createArrayNode().add(input);
        var messages = new ArrayList<JsonNode>();
        for (var item : items) {
            if (item.isTextual()) {
                messages.add(message("user", item.deepCopy()));
                continue;
            }
            if (!item.isObject()) {
                throw OpenAiRequestException.invalid(
                    "input", "Responses input items must be strings or objects");
            }
            var type = item.path("type").asText("");
            if (type.isBlank() || "message".equals(type)) {
                var role = item.path("role").asText("");
                if (role.isBlank()) {
                    throw OpenAiRequestException.invalid(
                        "input.role", "Responses message input requires role");
                }
                if (!item.has("content")) {
                    throw OpenAiRequestException.invalid(
                        "input.content", "Responses message input requires content");
                }
                var normalized = message(role, responseContent(item.path("content")));
                for (var field : List.of("id", "status", "phase")) {
                    if (item.has(field)) normalized.set(field, item.path(field).deepCopy());
                }
                messages.add(normalized);
                continue;
            }
            if ("reasoning".equals(type)) {
                if (item.hasNonNull("encrypted_content") && !item.path("encrypted_content").asText().isBlank()
                    && item.path("summary").isEmpty()) {
                    throw OpenAiRequestException.unsupported("input.encrypted_content", "opaque encrypted reasoning is not supported by these providers");
                }
                // Reasoning items remain in raw input for round trips and stored history;
                // they are not converted into user-visible prompt instructions.
                continue;
            }
            if (Set.of("function_call_output", "custom_tool_call_output").contains(type)) {
                var callId = item.path("call_id").asText(item.path("id").asText(""));
                if (callId.isBlank() || !item.has("output")) {
                    throw OpenAiRequestException.invalid(
                        "input", "Responses function_call_output requires call_id and output");
                }
                var output = item.path("output");
                var content = output.isTextual() || output.isArray()
                    ? output.deepCopy() : objectMapper.getNodeFactory().textNode(output.toString());
                var message = message("tool", content);
                message.put("tool_call_id", callId);
                messages.add(message);
                continue;
            }
            if (Set.of("function_call", "custom_tool_call").contains(type)) {
                var name = item.path("name").asText("");
                if (name.isBlank()) {
                    throw OpenAiRequestException.invalid(
                        "input.name", "Responses function_call requires name");
                }
                var callId = item.path("call_id").asText(item.path("id").asText(""));
                if (callId.isBlank()) {
                    throw OpenAiRequestException.invalid(
                        "input.call_id", "Responses function_call requires call_id");
                }
                if (item.has("arguments") && !item.path("arguments").isTextual()) {
                    throw OpenAiRequestException.invalid(
                        "input.arguments", "Responses function_call arguments must be a string");
                }
                var custom = "custom_tool_call".equals(type);
                if (custom && !item.path("input").isTextual()) {
                    throw OpenAiRequestException.invalid("input.input", "custom tool call requires string input");
                }
                var namespace = item.path("namespace").asText("");
                var upstreamName = OpenAiToolBridge.upstreamName(name, namespace, custom);
                var arguments = custom ? objectMapper.writeValueAsString(
                    objectMapper.createObjectNode().put("input", item.path("input").asText()))
                    : item.path("arguments").asText("{}");
                var message = !messages.isEmpty() && messages.getLast().has("tool_calls")
                    ? (ObjectNode) messages.getLast() : message("assistant", objectMapper.getNodeFactory().textNode(""));
                if (!message.has("tool_calls")) message.putArray("tool_calls");
                var call = ((ArrayNode) message.path("tool_calls")).addObject()
                    .put("id", callId)
                    .put("type", "function");
                call.putObject("function")
                    .put("name", upstreamName)
                    .put("arguments", arguments);
                if (messages.isEmpty() || messages.getLast() != message) messages.add(message);
                continue;
            }
            throw OpenAiRequestException.unsupported(
                "input.type", "unsupported Responses input item type: " + type);
        }
        return List.copyOf(messages);
    }

    private JsonNode responseContent(JsonNode content) {
        if (!content.isArray()) return content.deepCopy();
        var normalized = objectMapper.createArrayNode();
        for (var part : content) {
            if ("refusal".equals(part.path("type").asText())) {
                if (!part.path("refusal").isTextual()) {
                    throw OpenAiRequestException.invalid("input.content.refusal", "refusal must be a string");
                }
                normalized.addObject().put("type", "output_text").put("text", part.path("refusal").asText());
            } else normalized.add(part.deepCopy());
        }
        return normalized;
    }

    private ObjectNode message(String role, JsonNode content) {
        return objectMapper.createObjectNode()
            .put("role", role)
            .set("content", content);
    }

    private Map<String, Object> providerOptions(
        ObjectNode raw,
        String providerId,
        boolean allowForeignProviderOptions
    ) {
        var options = raw.path("provider_options");
        if (options.isMissingNode() || options.isNull()) {
            return Map.of();
        }
        if (!options.isObject()) {
            throw OpenAiRequestException.invalid(
                "provider_options", "provider_options must be an object");
        }
        var foreign = new ArrayList<String>();
        options.propertyNames().forEach(name -> {
            if (!providerId.equals(name)) foreign.add(name);
        });
        if (!allowForeignProviderOptions && !foreign.isEmpty()) {
            throw OpenAiRequestException.unknownProviderOption(
                "provider_options." + foreign.getFirst(),
                "provider_options contains a namespace that does not match the resolved provider");
        }
        var own = options.path(providerId);
        if (own.isMissingNode() || own.isNull()) {
            return Map.of();
        }
        if (!own.isObject()) {
            throw OpenAiRequestException.invalid(
                "provider_options." + providerId,
                "provider_options." + providerId + " must be an object");
        }
        return Map.copyOf(objectMapper.convertValue(
            own,
            new TypeReference<Map<String, Object>>() {}));
    }

    private void validateShape(CanonicalRequest.Protocol protocol, ObjectNode raw) {
        requireTypes(raw, List.of(
            "temperature", "top_p", "frequency_penalty", "presence_penalty"),
            JsonNode::isNumber, "must be a number");
        requireTypes(raw, List.of(
            "max_tokens", "max_completion_tokens", "max_output_tokens", "max_tool_calls",
            "n", "seed", "top_logprobs"),
            JsonNode::isIntegralNumber, "must be an integer");
        requireTypes(raw, List.of(
            "stream", "parallel_tool_calls", "store", "background", "logprobs"),
            JsonNode::isBoolean, "must be a boolean");
        requireTypes(raw, List.of(
            "model", "reasoning_effort", "previous_response_id", "prompt_cache_key",
            "safety_identifier", "service_tier", "truncation", "user"),
            JsonNode::isTextual, "must be a string");
        requireTypes(raw, List.of(
            "metadata", "reasoning", "response_format", "stream_options", "text", "client_metadata"),
            JsonNode::isObject, "must be an object");
        requireTypes(raw, List.of("include", "modalities", "tools"),
            JsonNode::isArray, "must be an array");
        if (raw.has("stream") && !raw.path("stream").isBoolean()) {
            throw OpenAiRequestException.invalid("stream", "stream must be a boolean");
        }
        if (raw.has("tools") && !raw.path("tools").isArray()) {
            throw OpenAiRequestException.invalid("tools", "tools must be an array");
        }
        if (raw.has("reasoning") && !raw.path("reasoning").isObject()) {
            throw OpenAiRequestException.invalid("reasoning", "reasoning must be an object");
        }
        if (raw.has("stream_options") && !raw.path("stream_options").isObject()) {
            throw OpenAiRequestException.invalid(
                "stream_options", "stream_options must be an object");
        }
        var toolChoice = raw.path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()
            && !toolChoice.isTextual() && !toolChoice.isObject()) {
            throw OpenAiRequestException.invalid(
                "tool_choice", "tool_choice must be a string or object");
        }
        var effort = raw.path("reasoning").path("effort");
        if (!effort.isMissingNode() && !effort.isNull() && !effort.isTextual()) {
            throw OpenAiRequestException.invalid(
                "reasoning.effort", "reasoning.effort must be a string");
        }
        if (protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            && raw.has("messages") && !raw.path("messages").isArray()) {
            throw OpenAiRequestException.invalid("messages", "messages must be an array");
        }
        if (protocol == CanonicalRequest.Protocol.RESPONSES
            && raw.has("instructions") && !raw.path("instructions").isTextual()
            && !raw.path("instructions").isNull()) {
            throw OpenAiRequestException.invalid(
                "instructions", "Responses instructions must be a string");
        }
    }

    private void requireTypes(
        ObjectNode raw,
        List<String> fields,
        java.util.function.Predicate<JsonNode> accepted,
        String message
    ) {
        for (var field : fields) {
            var value = raw.path(field);
            if (!value.isMissingNode() && !value.isNull() && !accepted.test(value)) {
                throw OpenAiRequestException.invalid(field, field + " " + message);
            }
        }
    }

    private void rejectLimitConflicts(ObjectNode raw) {
        var present = List.of("max_tokens", "max_completion_tokens", "max_output_tokens")
            .stream().filter(field -> raw.has(field) && !raw.path(field).isNull()).toList();
        if (present.size() > 1) {
            throw OpenAiRequestException.conflict(
                String.join(",", present),
                "token limit aliases cannot be supplied together: " + String.join(", ", present));
        }
        for (var field : present) {
            if (raw.path(field).asLong() <= 0) {
                throw OpenAiRequestException.invalid(field, field + " must be positive");
            }
        }
        var flatEffort = raw.path("reasoning_effort");
        var nestedEffort = raw.path("reasoning").path("effort");
        for (var effort : List.of(flatEffort, nestedEffort)) {
            if (effort.isTextual() && !Set.of(
                "auto", "none", "minimal", "low", "medium", "high")
                .contains(effort.asText().trim().toLowerCase())) {
                throw OpenAiRequestException.invalid(
                    "reasoning_effort",
                    "reasoning effort must be one of auto, none, minimal, low, medium, high");
            }
        }
        if (flatEffort.isTextual() && nestedEffort.isTextual()
            && !flatEffort.asText().equalsIgnoreCase(nestedEffort.asText())) {
            throw OpenAiRequestException.conflict(
                "reasoning_effort", "reasoning_effort conflicts with reasoning.effort");
        }
    }
}
