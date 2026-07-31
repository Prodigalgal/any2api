package com.any2api.provider.qwen;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class QwenRequestMapper {
    private final ObjectMapper mapper;
    private final QwenProperties properties;

    QwenRequestMapper(ObjectMapper mapper, QwenProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    ObjectNode prepare(CanonicalRequest request, String chatId) {
        var messages = new ArrayList<QwenPreparedMessage>();
        for (var source : request.messages()) {
            var role = normalizeRole(source.path("role").asText("user"));
            var content = content(source.path("content"));
            if ("assistant".equals(role) && source.path("tool_calls").isArray()) {
                content += "\n" + source.path("tool_calls").toString();
            }
            messages.add(new QwenPreparedMessage(role, content, List.of()));
        }
        return prepare(request, chatId, messages);
    }

    ObjectNode prepare(
        CanonicalRequest request,
        String chatId,
        List<QwenPreparedMessage> preparedMessages
    ) {
        var messages = foldSystemMessages(preparedMessages);
        var ids = messages.stream().map(ignored -> UUID.randomUUID().toString()).toList();
        var upstream = mapper.createArrayNode();
        var feature = featureConfig(request);
        var timestamp = java.time.Instant.now().getEpochSecond();
        for (var index = 0; index < messages.size(); index++) {
            var source = messages.get(index);
            var role = normalizeRole(source.role());
            var message = mapper.createObjectNode()
                .putNull("id")
                .put("fid", ids.get(index))
                .put("role", role)
                .put("content", source.content())
                .put("user_action", "chat")
                .put("timestamp", timestamp)
                .put("model", "assistant".equals(role) ? request.model() : "")
                .put("chat_type", "t2t")
                .put("sub_chat_type", "t2t")
                .set("feature_config", feature.deepCopy());
            if (index == 0) {
                message.putNull("parentId").putNull("parent_id");
            } else {
                message.put("parentId", ids.get(index - 1)).put("parent_id", ids.get(index - 1));
            }
            var children = message.putArray("childrenIds");
            if (index + 1 < ids.size()) children.add(ids.get(index + 1));
            message.set("files", mapper.valueToTree(source.files()));
            var models = message.putArray("models");
            if ("user".equals(role)) models.add(request.model());
            message.set("extra", mapper.createObjectNode().set("meta",
                mapper.createObjectNode().put("subChatType", "t2t")));
            upstream.add(message);
        }
        var body = mapper.createObjectNode()
            .put("stream", true)
            .put("version", properties.getRequestVersion())
            .put("incremental_output", true)
            .put("model", request.model())
            .put("chat_id", chatId)
            .put("chat_mode", "normal")
            .putNull("parent_id")
            .put("timestamp", timestamp)
            .set("messages", upstream);
        copyNumber(request, body, "temperature", "temperature");
        copyNumber(request, body, "top_p", "top_p");
        if (request.generation().containsKey("max_completion_tokens")) {
            body.put("max_tokens", ((Number) request.generation().get("max_completion_tokens")).longValue());
        } else if (request.generation().containsKey("max_output_tokens")) {
            body.put("max_tokens", ((Number) request.generation().get("max_output_tokens")).longValue());
        } else copyNumber(request, body, "max_tokens", "max_tokens");
        return body;
    }

    private ArrayList<QwenPreparedMessage> foldSystemMessages(
        List<QwenPreparedMessage> source
    ) {
        var instructions = new ArrayList<String>();
        var messages = new ArrayList<QwenPreparedMessage>();
        for (var message : source) {
            if (List.of("system", "developer").contains(message.role().toLowerCase())) {
                if (!message.content().isBlank()) instructions.add(message.content());
            } else {
                messages.add(message);
            }
        }
        if (instructions.isEmpty()) return messages;
        var prefix = "[System instructions]\n" + String.join("\n\n", instructions);
        for (var index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if ("user".equalsIgnoreCase(message.role())) {
                var content = message.content().isBlank()
                    ? prefix : prefix + "\n\n" + message.content();
                messages.set(index, new QwenPreparedMessage(
                    message.role(), content, message.files()));
                return messages;
            }
        }
        messages.add(0, new QwenPreparedMessage("user", prefix, List.of()));
        return messages;
    }

    private ObjectNode featureConfig(CanonicalRequest request) {
        var options = request.providerOptions();
        var effort = String.valueOf(request.reasoning().getOrDefault("effort",
            request.rawRequest().path("reasoning_effort").asText("auto"))).toLowerCase();
        var rawMode = request.rawRequest().path("thinking_mode").asText("");
        var mode = string(options.get("thinking_mode"), rawMode.isBlank() ? switch (effort) {
            case "none", "minimal" -> "Fast";
            case "auto" -> "Auto";
            default -> "Thinking";
        } : normalizeMode(rawMode));
        if (request.rawRequest().path("enable_thinking").isBoolean()
            && !options.containsKey("thinking_mode")) {
            mode = request.rawRequest().path("enable_thinking").asBoolean() ? "Thinking" : "Fast";
        }
        var rawSearch = request.rawRequest().path("web_search");
        if (!rawSearch.isBoolean()) rawSearch = request.rawRequest().path("enable_search");
        if (!rawSearch.isBoolean()) rawSearch = request.rawRequest().path("search");
        var toolChoiceNone = "none".equalsIgnoreCase(
            request.rawRequest().path("tool_choice").asText(""));
        var searchFallback = toolChoiceNone ? false
            : rawSearch.isBoolean() ? rawSearch.asBoolean() : hasSearchTool(request.tools());
        var search = bool(options.get("web_search"), searchFallback);
        var output = mapper.createObjectNode()
            .put("thinking_enabled", !"Fast".equalsIgnoreCase(mode))
            .put("output_schema", "phase")
            .put("research_mode", "normal")
            .put("auto_thinking", "Auto".equalsIgnoreCase(mode))
            .put("thinking_mode", mode)
            .put("thinking_format", "summary")
            .put("auto_search", search);
        var budget = options.getOrDefault("thinking_budget",
            request.rawRequest().path("thinking_budget").isNumber()
                ? request.rawRequest().path("thinking_budget").numberValue() : null);
        if (budget instanceof Number number && number.longValue() > 0) {
            output.put("thinking_budget", number.longValue());
        }
        return output;
    }

    private boolean hasSearchTool(List<JsonNode> tools) {
        return tools.stream().map(tool -> tool.path("type").asText(""))
            .anyMatch(type -> List.of("web_search", "web_search_preview", "search").contains(type));
    }

    private String content(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var output = new ArrayList<String>();
        for (var part : value) {
            if (part.isTextual()) output.add(part.asText());
            else if (part.isObject()) output.add(part.path("text").asText(""));
        }
        return String.join("\n", output);
    }

    private String normalizeRole(String role) {
        return switch (role.toLowerCase()) {
            case "developer" -> "system";
            case "assistant", "system", "tool" -> role.toLowerCase();
            default -> "user";
        };
    }

    private void copyNumber(CanonicalRequest request, ObjectNode target, String source, String field) {
        var value = request.generation().get(source);
        if (value instanceof Number number) target.put(field, number.doubleValue());
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private String string(Object value, String fallback) {
        var result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private String normalizeMode(String value) {
        return switch (value.trim().toLowerCase()) {
            case "auto" -> "Auto";
            case "fast", "disabled", "off", "false", "none" -> "Fast";
            default -> "Thinking";
        };
    }
}
