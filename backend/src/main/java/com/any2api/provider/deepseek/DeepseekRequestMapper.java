package com.any2api.provider.deepseek;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class DeepseekRequestMapper {
    private final ObjectMapper mapper;

    DeepseekRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode prepare(CanonicalRequest request, String sessionId) {
        return mapper.createObjectNode()
            .put("chat_session_id", sessionId)
            .putNull("parent_message_id")
            .put("model_type", request.model())
            .put("prompt", prompt(request.messages()))
            .set("ref_file_ids", mapper.createArrayNode())
            .put("thinking_enabled", thinking(request))
            .put("search_enabled", search(request))
            .putNull("action")
            .put("preempt", false);
    }

    boolean thinking(CanonicalRequest request) {
        var option = booleanOption(request, "thinking_enabled");
        if (option != null) return option;
        var raw = request.rawRequest().path("enable_thinking");
        if (raw.isBoolean()) return raw.asBoolean();
        var effort = String.valueOf(request.reasoning().getOrDefault(
            "effort", request.rawRequest().path("reasoning_effort").asText("")))
            .trim().toLowerCase();
        if (!effort.isBlank()) return !List.of("none", "minimal").contains(effort);
        return "expert".equals(request.model());
    }

    boolean search(CanonicalRequest request) {
        var option = booleanOption(request, "search_enabled");
        if (option != null) return option;
        for (var name : List.of("web_search", "enable_search", "search")) {
            var raw = request.rawRequest().path(name);
            if (raw.isBoolean()) return raw.asBoolean();
        }
        return request.tools().stream().anyMatch(tool -> List.of(
            "web_search", "web_search_preview", "search")
            .contains(tool.path("type").asText("")));
    }

    static String prompt(List<JsonNode> messages) {
        var sections = new ArrayList<String>();
        for (var message : messages) {
            var role = message.path("role").asText("user").trim().toLowerCase();
            var text = text(message.path("content"));
            if (!text.isBlank()) sections.add("[" + role + "]\n" + text);
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("DeepSeek prompt is empty");
        return String.join("\n\n", sections);
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return "";
        var output = new ArrayList<String>();
        for (var item : content) {
            var type = item.path("type").asText("text");
            if (!List.of("text", "input_text", "output_text").contains(type)) {
                throw new IllegalArgumentException(
                    "DeepSeek text adapter does not support content type " + type);
            }
            var value = item.path("text").asText("");
            if (!value.isBlank()) output.add(value);
        }
        return String.join("\n", output);
    }

    private Boolean booleanOption(CanonicalRequest request, String name) {
        var value = request.providerOptions().get(name);
        return value instanceof Boolean bool ? bool : null;
    }
}
