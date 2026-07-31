package com.any2api.provider.grok_console;

import com.any2api.protocol.CanonicalRequest;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GrokConsoleRequestMapper {
    private static final Set<String> STATEFUL_FIELDS = Set.of(
        "metadata", "previous_response_id", "service_tier", "prompt_cache_key",
        "background", "conversation", "provider_options");

    private final ObjectMapper mapper;

    GrokConsoleRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode prepare(CanonicalRequest request) {
        var spec = GrokConsoleModelCatalog.require(request.model());
        var payload = request.protocol() == CanonicalRequest.Protocol.RESPONSES
            ? (ObjectNode) request.rawRequest().deepCopy()
            : fromChat(request);
        payload.put("model", spec.id()).put("stream", true).put("store", false);
        STATEFUL_FIELDS.forEach(payload::remove);
        normalizeLimits(payload, spec);
        normalizeReasoning(payload, spec);
        if (!payload.has("include")) {
            payload.putArray("include").add("reasoning.encrypted_content");
        }
        payload.set("tools", tools(request.tools()));
        return payload;
    }

    private ObjectNode fromChat(CanonicalRequest request) {
        var payload = mapper.createObjectNode();
        payload.set("input", input(request.messages()));
        if (request.rawRequest().has("temperature")) {
            payload.set("temperature", request.rawRequest().path("temperature").deepCopy());
        }
        if (request.rawRequest().has("tool_choice")) {
            payload.set("tool_choice", request.rawRequest().path("tool_choice").deepCopy());
        }
        return payload;
    }

    private ArrayNode input(java.util.List<JsonNode> messages) {
        var output = mapper.createArrayNode();
        for (var message : messages) {
            var role = message.path("role").asText("user").toLowerCase();
            if ("tool".equals(role)) {
                output.add(mapper.createObjectNode().put("type", "function_call_output")
                    .put("call_id", message.path("tool_call_id").asText(""))
                    .put("output", text(message.path("content"))));
                continue;
            }
            if ("assistant".equals(role) && message.path("tool_calls").isArray()) {
                for (var call : message.path("tool_calls")) {
                    var function = call.path("function");
                    output.add(mapper.createObjectNode().put("type", "function_call")
                        .put("id", call.path("id").asText(""))
                        .put("call_id", call.path("id").asText(""))
                        .put("name", function.path("name").asText(""))
                        .put("arguments", function.path("arguments").asText("{}")));
                }
            }
            var content = message.path("content");
            if (content.isArray()) {
                appendTextBlocks(output, role, content);
            } else if (!content.asText("").isBlank()) {
                output.add(message(role, content.asText()));
            }
        }
        return output;
    }

    private void appendTextBlocks(ArrayNode output, String role, JsonNode blocks) {
        var textParts = mapper.createArrayNode();
        for (var block : blocks) {
            var type = block.path("type").asText("text");
            if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type)) {
                textParts.add(mapper.createObjectNode()
                    .put("type", "assistant".equals(role) ? "output_text" : "input_text")
                    .put("text", block.path("text").asText("")));
            }
        }
        if (!textParts.isEmpty()) {
            var normalizedRole = "system".equals(role) ? "developer" : role;
            output.add(mapper.createObjectNode().put("type", "message")
                .put("role", normalizedRole).set("content", textParts));
        }
    }

    private ObjectNode message(String role, String text) {
        var normalizedRole = "system".equals(role) ? "developer" : role;
        var parts = mapper.createArrayNode().add(mapper.createObjectNode()
            .put("type", "assistant".equals(role) ? "output_text" : "input_text")
            .put("text", text));
        return mapper.createObjectNode().put("type", "message")
            .put("role", normalizedRole).set("content", parts);
    }

    private ArrayNode tools(java.util.List<JsonNode> rawTools) {
        var output = mapper.createArrayNode();
        for (var raw : rawTools) {
            var function = raw.path("function");
            var source = function.isObject() ? function : raw;
            var name = source.path("name").asText("").trim();
            if (name.isBlank()) continue;
            var tool = mapper.createObjectNode().put("type", "function").put("name", name);
            if (source.has("description")) tool.set("description", source.path("description").deepCopy());
            tool.set("parameters", source.has("parameters")
                ? source.path("parameters").deepCopy()
                : mapper.createObjectNode().put("type", "object"));
            output.add(tool);
        }
        return output;
    }

    private void normalizeLimits(ObjectNode payload, GrokConsoleModelCatalog.ModelSpec spec) {
        if (!payload.has("max_output_tokens")) {
            var requested = payload.path("max_tokens").asInt(spec.maxOutputTokens());
            payload.put("max_output_tokens", Math.min(Math.max(1, requested), spec.maxOutputTokens()));
        }
        payload.remove("max_tokens");
        payload.remove("max_completion_tokens");
    }

    private void normalizeReasoning(ObjectNode payload, GrokConsoleModelCatalog.ModelSpec spec) {
        if (!spec.reasoning()) {
            payload.remove("reasoning");
            return;
        }
        var reasoning = payload.path("reasoning").isObject()
            ? (ObjectNode) payload.path("reasoning").deepCopy() : mapper.createObjectNode();
        if (!reasoning.has("effort") && !spec.defaultEffort().isBlank()) {
            reasoning.put("effort", spec.defaultEffort());
        }
        payload.set("reasoning", reasoning);
    }

    private String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.toString();
        var value = new StringBuilder();
        content.forEach(item -> value.append(item.path("text").asText(item.asText(""))));
        return value.toString();
    }
}
