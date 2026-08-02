package com.any2api.protocol;

import com.any2api.routing.ResolvedRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final ObjectMapper objectMapper;

    public CanonicalRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalRequest parse(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw
    ) {
        return parse(protocol, route, raw, false);
    }

    public CanonicalRequest parseCandidate(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw
    ) {
        return parse(protocol, route, raw, true);
    }

    private CanonicalRequest parse(
        CanonicalRequest.Protocol protocol,
        ResolvedRoute route,
        ObjectNode raw,
        boolean allowForeignProviderOptions
    ) {
        validateShape(protocol, raw);
        rejectLimitConflicts(raw);
        var requestId = UUID.randomUUID().toString();
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
        var tools = elements(raw.path("tools"));
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
                messages.add(message(role, item.path("content").deepCopy()));
                continue;
            }
            if ("function_call_output".equals(type)) {
                var callId = item.path("call_id").asText(item.path("id").asText(""));
                if (callId.isBlank() || !item.has("output")) {
                    throw OpenAiRequestException.invalid(
                        "input", "Responses function_call_output requires call_id and output");
                }
                var output = item.path("output");
                var content = output.isTextual()
                    ? output.deepCopy() : objectMapper.getNodeFactory().textNode(output.toString());
                var message = message("tool", content);
                message.put("tool_call_id", callId);
                messages.add(message);
                continue;
            }
            if ("function_call".equals(type)) {
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
                var message = message("assistant", objectMapper.getNodeFactory().textNode(""));
                var call = message.putArray("tool_calls").addObject()
                    .put("id", callId)
                    .put("type", "function");
                call.putObject("function")
                    .put("name", name)
                    .put("arguments", item.path("arguments").asText("{}"));
                messages.add(message);
                continue;
            }
            throw OpenAiRequestException.unsupported(
                "input.type", "unsupported Responses input item type: " + type);
        }
        return List.copyOf(messages);
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
            "metadata", "reasoning", "response_format", "stream_options", "text"),
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
        if (flatEffort.isTextual() && nestedEffort.isTextual()
            && !flatEffort.asText().equalsIgnoreCase(nestedEffort.asText())) {
            throw OpenAiRequestException.conflict(
                "reasoning_effort", "reasoning_effort conflicts with reasoning.effort");
        }
    }
}
