package com.any2api.provider.glm;

import com.any2api.protocol.CanonicalRequest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GlmRequestMapper {
    private final ObjectMapper mapper;

    GlmRequestMapper(ObjectMapper mapper) { this.mapper = mapper; }

    ChatSeed prepareChat(CanonicalRequest request, long timestamp) {
        var userMessageId = UUID.randomUUID().toString();
        var prompt = lastUserPrompt(request);
        var message = mapper.createObjectNode()
            .put("id", userMessageId)
            .putNull("parentId")
            .put("role", "user")
            .put("content", prompt)
            .put("timestamp", timestamp / 1000);
        message.putArray("childrenIds");
        message.putArray("models").add(request.model());
        var messages = mapper.createObjectNode().set(userMessageId, message);
        var history = mapper.createObjectNode()
            .set("messages", messages);
        history.put("currentId", userMessageId);
        var chat = mapper.createObjectNode()
            .put("id", "")
            .put("title", "New Chat")
            .set("params", mapper.createObjectNode())
            .set("history", history)
            .set("tags", mapper.createArrayNode())
            .set("flags", mapper.createArrayNode())
            .set("features", defaultChatFeatures())
            .set("mcp_servers", mapper.createArrayNode())
            .put("enable_thinking", thinkingEnabled(request))
            .put("reasoning_effort", reasoningEffort(request))
            .put("auto_web_search", webSearch(request))
            .put("message_version", 1)
            .set("extra", mapper.createObjectNode())
            .put("timestamp", timestamp)
            .put("type", "default");
        chat.putArray("models").add(request.model());
        return new ChatSeed(
            mapper.createObjectNode().set("chat", chat),
            userMessageId,
            prompt);
    }

    ObjectNode prepareCompletion(
        CanonicalRequest request,
        ChatSeed seed,
        String chatId,
        String email,
        long timestamp
    ) {
        var messages = canonicalMessages(request);
        var body = mapper.createObjectNode()
            .put("stream", true)
            .put("model", request.model())
            .set("messages", messages)
            .put("signature_prompt", seed.prompt())
            .set("params", generationParams(request))
            .set("extra", mapper.createObjectNode())
            .set("features", completionFeatures(request))
            .set("variables", variables(email, timestamp))
            .put("chat_id", chatId)
            .put("id", UUID.randomUUID().toString())
            .put("current_user_message_id", seed.userMessageId())
            .putNull("current_user_message_parent_id")
            .set("background_tasks", mapper.createObjectNode()
                .put("title_generation", true)
                .put("tags_generation", true));
        return body;
    }

    private ArrayNode canonicalMessages(CanonicalRequest request) {
        var output = mapper.createArrayNode();
        if (!request.tools().isEmpty()) {
            output.add(mapper.createObjectNode()
                .put("role", "system")
                .put("content", "Available function tools:\n"
                    + mapper.writeValueAsString(request.tools())));
        }
        for (var source : request.messages()) {
            var role = normalizeRole(source.path("role").asText("user"));
            var content = content(source.path("content"));
            if ("assistant".equals(role) && source.path("tool_calls").isArray()) {
                content += "\n" + source.path("tool_calls");
            }
            output.add(mapper.createObjectNode().put("role", role).put("content", content));
        }
        return output;
    }

    private ObjectNode generationParams(CanonicalRequest request) {
        var output = mapper.createObjectNode();
        copyNumber(request, output, "temperature", "temperature");
        copyNumber(request, output, "top_p", "top_p");
        if (request.generation().get("max_completion_tokens") instanceof Number number) {
            output.put("max_tokens", number.longValue());
        } else if (request.generation().get("max_output_tokens") instanceof Number number) {
            output.put("max_tokens", number.longValue());
        } else copyNumber(request, output, "max_tokens", "max_tokens");
        return output;
    }

    private ObjectNode completionFeatures(CanonicalRequest request) {
        return mapper.createObjectNode()
            .put("image_generation", false)
            .put("web_search", false)
            .put("auto_web_search", webSearch(request))
            .put("preview_mode", bool(request, "preview_mode", true))
            .set("flags", mapper.createArrayNode())
            .put("vlm_tools_enable", false)
            .put("vlm_web_search_enable", false)
            .put("vlm_website_mode", false)
            .put("enable_thinking", thinkingEnabled(request))
            .put("reasoning_effort", reasoningEffort(request));
    }

    private ObjectNode variables(String email, long timestamp) {
        var now = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return mapper.createObjectNode()
            .put("{{USER_NAME}}", email)
            .put("{{USER_LOCATION}}", "Unknown")
            .put("{{CURRENT_DATETIME}}", now.format(DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss")))
            .put("{{CURRENT_DATE}}", now.toLocalDate().toString())
            .put("{{CURRENT_TIME}}", now.toLocalTime().withNano(0).toString())
            .put("{{CURRENT_WEEKDAY}}", now.getDayOfWeek().toString())
            .put("{{CURRENT_TIMEZONE}}", now.getZone().getId())
            .put("{{USER_LANGUAGE}}", "en-US");
    }

    private ArrayNode defaultChatFeatures() {
        return mapper.createArrayNode().add(mapper.createObjectNode()
            .put("server", "tool_selector_h")
            .put("status", "hidden")
            .put("type", "tool_selector"));
    }

    private String lastUserPrompt(CanonicalRequest request) {
        var candidates = new ArrayList<String>();
        for (var message : request.messages()) {
            if ("user".equals(normalizeRole(message.path("role").asText("user")))) {
                var value = content(message.path("content"));
                if (!value.isBlank()) candidates.add(value);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("GLM request requires a user message");
        }
        return candidates.getLast();
    }

    private String content(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var output = new ArrayList<String>();
        for (var part : value) {
            if (part.isTextual()) output.add(part.asText());
            else if (part.isObject() && part.path("text").isTextual()) {
                output.add(part.path("text").asText());
            }
        }
        return String.join("\n", output);
    }

    private boolean thinkingEnabled(CanonicalRequest request) {
        if (request.providerOptions().get("enable_thinking") instanceof Boolean value) {
            return value;
        }
        var effort = reasoningEffort(request);
        return !List.of("none", "minimal", "low").contains(effort);
    }

    private String reasoningEffort(CanonicalRequest request) {
        var value = String.valueOf(request.providerOptions().getOrDefault(
            "reasoning_effort",
            request.reasoning().getOrDefault("effort",
                request.rawRequest().path("reasoning_effort").asText("max"))))
            .trim().toLowerCase();
        return value.isBlank() ? "max" : value;
    }

    private boolean webSearch(CanonicalRequest request) {
        return bool(request, "web_search", false);
    }

    private boolean bool(CanonicalRequest request, String field, boolean fallback) {
        var option = request.providerOptions().get(field);
        if (option instanceof Boolean value) return value;
        var raw = request.rawRequest().path(field);
        return raw.isBoolean() ? raw.asBoolean() : fallback;
    }

    private void copyNumber(
        CanonicalRequest request,
        ObjectNode target,
        String source,
        String field
    ) {
        if (request.generation().get(source) instanceof Number number) {
            target.put(field, number.doubleValue());
        }
    }

    private String normalizeRole(String role) {
        return switch (role.toLowerCase()) {
            case "developer" -> "system";
            case "assistant", "system", "tool" -> role.toLowerCase();
            default -> "user";
        };
    }

    record ChatSeed(ObjectNode body, String userMessageId, String prompt) {}
}
