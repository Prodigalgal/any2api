package com.any2api.provider.grok;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class GrokRequestMapper {

    private static final List<String> FORWARDED_FIELDS = List.of(
        "temperature", "top_p", "tool_choice", "parallel_tool_calls", "prompt_cache_key",
        "user", "max_output_tokens", "stream_tool_calls");

    private final ObjectMapper mapper;

    GrokRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    GrokPreparedRequest prepare(CanonicalRequest request) {
        var raw = (ObjectNode) request.rawRequest().deepCopy();
        var payload = request.protocol() != CanonicalRequest.Protocol.RESPONSES
            ? fromChat(raw, request)
            : fromResponses(raw, request.model());
        payload.remove("provider_options");
        var conversationId = firstText(
            raw,
            "prompt_cache_key", "conversation_id", "conversation", "thread_id", "session_id");
        if (conversationId == null && raw.path("metadata").isObject()) {
            conversationId = firstText(
                (ObjectNode) raw.path("metadata"),
                "prompt_cache_key", "session_id", "sessionId", "thread_id",
                "conversation_id", "user_id");
        }
        if (conversationId != null && !payload.hasNonNull("prompt_cache_key")) {
            payload.put("prompt_cache_key", conversationId);
        }
        return new GrokPreparedRequest(payload, conversationId);
    }

    private ObjectNode fromResponses(ObjectNode raw, String model) {
        var payload = raw.deepCopy();
        payload.put("model", model);
        payload.put("stream", true);
        if (!payload.has("store")) {
            payload.put("store", false);
        }
        if (!payload.has("include")) {
            payload.putArray("include").add("reasoning.encrypted_content");
        }
        return payload;
    }

    private ObjectNode fromChat(ObjectNode raw, CanonicalRequest request) {
        var payload = mapper.createObjectNode()
            .put("model", request.model())
            .put("stream", true)
            .put("store", false)
            .put("parallel_tool_calls", raw.path("parallel_tool_calls").asBoolean(true));
        payload.putArray("include").add("reasoning.encrypted_content");
        payload.set("input", messages(mapper.valueToTree(request.messages())));
        payload.put("instructions", raw.path("instructions").asText(""));

        var tools = tools(raw.path("tools"));
        var skipSearch = raw.path("_skip_x_search").asBoolean(false)
            || Boolean.TRUE.equals(request.providerOptions().get("skip_x_search"));
        if (!skipSearch && !hasToolType(tools, "x_search")) {
            tools.insert(0, mapper.createObjectNode().put("type", "x_search"));
        }
        if (!tools.isEmpty()) {
            payload.set("tools", tools);
        }
        for (var field : FORWARDED_FIELDS) {
            if (raw.has(field) && !raw.path(field).isNull()) {
                payload.set(field, raw.path(field).deepCopy());
            }
        }
        if (!payload.has("max_output_tokens")) {
            if (raw.has("max_completion_tokens")) {
                payload.set("max_output_tokens", raw.path("max_completion_tokens").deepCopy());
            } else if (raw.has("max_tokens")) {
                payload.set("max_output_tokens", raw.path("max_tokens").deepCopy());
            }
        }
        payload.set("reasoning", reasoning(raw));
        return payload;
    }

    private ArrayNode messages(JsonNode rawMessages) {
        var output = mapper.createArrayNode();
        if (!rawMessages.isArray()) {
            return output;
        }
        for (var raw : rawMessages) {
            if (!raw.isObject()) {
                continue;
            }
            var role = raw.path("role").asText("user").trim().toLowerCase();
            if ("tool".equals(role)) {
                output.add(mapper.createObjectNode()
                    .put("type", "function_call_output")
                    .put("call_id", text(raw, "tool_call_id", "call_id", "call_node"))
                    .put("output", contentText(raw.path("content"))));
                continue;
            }
            if ("assistant".equals(role) && raw.path("tool_calls").isArray()) {
                for (var rawCall : raw.path("tool_calls")) {
                    var function = rawCall.path("function");
                    var name = function.path("name").asText(rawCall.path("name").asText(""));
                    if (!name.isBlank()) {
                        output.add(mapper.createObjectNode()
                            .put("type", "function_call")
                            .put("id", rawCall.path("id").asText(""))
                            .put("call_id", rawCall.path("id").asText("call_node"))
                            .put("name", name)
                            .put("arguments", function.path("arguments").asText(
                                rawCall.path("arguments").asText("{}"))));
                    }
                }
            }
            var content = contentText(raw.path("content")).trim();
            if (content.isBlank()) {
                continue;
            }
            var normalizedRole = switch (role) {
                case "system" -> "developer";
                case "assistant", "developer" -> role;
                default -> "user";
            };
            var partType = "assistant".equals(normalizedRole) ? "output_text" : "input_text";
            var parts = mapper.createArrayNode().add(
                mapper.createObjectNode().put("type", partType).put("text", content));
            output.add(mapper.createObjectNode()
                .put("type", "message")
                .put("role", normalizedRole)
                .set("content", parts));
        }
        return output;
    }

    private ArrayNode tools(JsonNode rawTools) {
        var output = mapper.createArrayNode();
        if (!rawTools.isArray()) {
            return output;
        }
        for (var raw : rawTools) {
            if (!raw.isObject()) {
                continue;
            }
            var function = raw.path("function");
            if (function.isObject() && !function.path("name").asText("").isBlank()) {
                var tool = mapper.createObjectNode()
                    .put("type", "function")
                    .put("name", function.path("name").asText());
                if (function.has("description")) {
                    tool.set("description", function.path("description").deepCopy());
                }
                tool.set("parameters", function.has("parameters")
                    ? function.path("parameters").deepCopy()
                    : mapper.createObjectNode().put("type", "object").set(
                        "properties", mapper.createObjectNode()));
                output.add(tool);
            } else {
                output.add(raw.deepCopy());
            }
        }
        return output;
    }

    private ObjectNode reasoning(ObjectNode raw) {
        if (raw.path("reasoning").isObject()) {
            var reasoning = (ObjectNode) raw.path("reasoning").deepCopy();
            if (!reasoning.has("summary")) {
                reasoning.put("summary", "auto");
            }
            return reasoning;
        }
        var effort = raw.path("reasoning_effort").asText("low");
        return mapper.createObjectNode().put("effort", effort).put("summary", "auto");
    }

    private String contentText(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            var result = new StringBuilder();
            for (var part : value) {
                if (part.isTextual()) {
                    result.append(part.asText());
                } else if (part.isObject()) {
                    result.append(part.path("text").asText(part.path("content").asText("")));
                }
            }
            return result.toString();
        }
        return value.isNull() || value.isMissingNode() ? "" : value.toString();
    }

    private boolean hasToolType(ArrayNode tools, String type) {
        for (var tool : tools) {
            if (type.equals(tool.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private String firstText(ObjectNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String primary, String secondary, String fallback) {
        var value = node.path(primary).asText("");
        return value.isBlank() ? node.path(secondary).asText(fallback) : value;
    }
}
