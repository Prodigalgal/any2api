package com.any2api.provider.minmax;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class MinmaxRequestMapper {
    private final ObjectMapper mapper;

    MinmaxRequestMapper(ObjectMapper mapper) { this.mapper = mapper; }

    MinmaxPreparedRequest prepare(CanonicalRequest request) {
        var media = new ArrayList<MinmaxMediaSource>();
        var options = request.providerOptions();
        var variant = string(options.get("variant"), defaultVariant(request));
        var model = mapper.createObjectNode()
            .put("provider_id", "minimax")
            .put("model_id", request.model());
        if (!variant.isBlank()) model.put("variant", variant);
        return new MinmaxPreparedRequest(
            prompt(request.messages(), media),
            model,
            string(options.get("agent_role"), "mavis"),
            bool(options.get("enable_team"), true),
            bool(options.get("worktree_mode"), false),
            media);
    }

    private String prompt(List<JsonNode> messages, List<MinmaxMediaSource> media) {
        var blocks = new ArrayList<String>();
        for (var message : messages) {
            var role = message.path("role").asText("user").toUpperCase();
            var content = content(message.path("content"), media);
            if ("ASSISTANT".equals(role) && message.path("tool_calls").isArray()) {
                content += "\n" + message.path("tool_calls").toString();
            }
            if ("TOOL".equals(role)) {
                role = "TOOL " + message.path("tool_call_id").asText("");
            }
            if (!content.isBlank()) blocks.add("[" + role + "]\n" + content);
        }
        return String.join("\n\n", blocks);
    }

    private String content(JsonNode value, List<MinmaxMediaSource> media) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var values = new ArrayList<String>();
        for (var part : value) {
            if (part.isTextual()) values.add(part.asText());
            else if (part.isObject()) {
                var type = part.path("type").asText("");
                if (List.of("text", "input_text", "output_text").contains(type)) {
                    values.add(part.path("text").asText(""));
                } else if (List.of("image_url", "input_image").contains(type)) {
                    var image = part.path("image_url");
                    var dataUrl = image.isTextual() ? image.asText("")
                        : image.path("url").asText("");
                    var filename = part.path("filename").asText("");
                    media.add(new MinmaxMediaSource(dataUrl, filename));
                } else {
                    throw new IllegalArgumentException(
                        "unsupported MinMax content block type: " + type);
                }
            }
        }
        return String.join("\n", values);
    }

    private String defaultVariant(CanonicalRequest request) {
        var effort = String.valueOf(request.reasoning().getOrDefault("effort",
            request.rawRequest().path("reasoning_effort").asText(""))).toLowerCase();
        return effort.isBlank() || List.of("none", "minimal").contains(effort) ? "" : "thinking";
    }

    private String string(Object value, String fallback) {
        var result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }
}
