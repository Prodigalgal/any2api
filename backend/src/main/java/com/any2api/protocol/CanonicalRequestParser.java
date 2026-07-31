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
        var requestId = UUID.randomUUID().toString();
        var stream = raw.path("stream").asBoolean(false);
        var messages = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? elements(raw.path("messages"))
            : responseMessages(raw.path("input"));
        if (protocol == CanonicalRequest.Protocol.RESPONSES && raw.has("instructions")
            && !raw.path("instructions").isNull()) {
            if (!raw.path("instructions").isTextual()) {
                throw new IllegalArgumentException("Responses instructions must be a string");
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
        var providerOptions = providerOptions(raw, route.providerId());
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
                throw new IllegalArgumentException("Responses input items must be strings or objects");
            }
            var type = item.path("type").asText("");
            if (type.isBlank() || "message".equals(type)) {
                var role = item.path("role").asText("user");
                if (!item.has("content")) {
                    throw new IllegalArgumentException("Responses message input requires content");
                }
                messages.add(message(role, item.path("content").deepCopy()));
                continue;
            }
            if ("function_call_output".equals(type)) {
                var callId = item.path("call_id").asText(item.path("id").asText(""));
                if (callId.isBlank() || !item.has("output")) {
                    throw new IllegalArgumentException(
                        "Responses function_call_output requires call_id and output");
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
                    throw new IllegalArgumentException("Responses function_call requires name");
                }
                var callId = item.path("call_id").asText(item.path("id").asText("call"));
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
            throw new IllegalArgumentException("unsupported Responses input item type: " + type);
        }
        return List.copyOf(messages);
    }

    private ObjectNode message(String role, JsonNode content) {
        return objectMapper.createObjectNode()
            .put("role", role)
            .set("content", content);
    }

    private Map<String, Object> providerOptions(ObjectNode raw, String providerId) {
        var options = raw.path("provider_options");
        if (!options.isObject()) {
            return Map.of();
        }
        var own = options.path(providerId);
        if (!own.isObject()) {
            return Map.of();
        }
        return Map.copyOf(objectMapper.convertValue(
            own,
            new TypeReference<Map<String, Object>>() {}));
    }
}
