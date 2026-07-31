package com.any2api.provider.mimo;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class MimoRequestMapper {
    private final ObjectMapper mapper;

    MimoRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    MimoPreparedRequest prepare(CanonicalRequest request) {
        var tools = tools(request.tools());
        var media = new ArrayList<MimoMediaSource>();
        var options = request.providerOptions();
        var modelConfig = mapper.createObjectNode()
            .put("enableThinking", thinking(request))
            .put("temperature", number(request, "temperature", 0.8))
            .put("topP", number(request, "top_p", 0.95))
            .put("webSearchStatus", text(options.get("web_search_status"),
                request.rawRequest().path("web_search_status").asText("disabled")))
            .put("model", request.model());
        var body = mapper.createObjectNode()
            .put("msgId", compactUuid())
            .put("conversationId", text(options.get("conversation_id"), compactUuid()))
            .put("query", messages(request.messages(), tools, media))
            .set("modelConfig", modelConfig);
        body.set("multiMedias", mapper.createArrayNode());
        body.set("attachments", mapper.createArrayNode());
        return new MimoPreparedRequest(body, tools, List.copyOf(media));
    }

    private String messages(
        List<JsonNode> messages,
        List<MimoTool> tools,
        List<MimoMediaSource> media
    ) {
        var system = new ArrayList<String>();
        var conversation = new ArrayList<String>();
        for (var message : messages) {
            var role = message.path("role").asText("user").toLowerCase();
            var content = content(message.path("content"), media);
            if ("system".equals(role) || "developer".equals(role)) {
                if (!content.isBlank()) system.add(content);
            } else if ("tool".equals(role)) {
                conversation.add("[TOOL " + message.path("tool_call_id").asText("") + "]\n" + content);
            } else if ("assistant".equals(role) && message.path("tool_calls").isArray()) {
                var calls = new ArrayList<String>();
                for (var call : message.path("tool_calls")) {
                    var function = call.path("function");
                    calls.add("TOOL_CALL: " + function.path("name").asText("")
                        + "(" + function.path("arguments").asText("{}") + ")");
                }
                conversation.add("[ASSISTANT]\n" + String.join("\n", calls));
            } else {
                conversation.add("[" + role.toUpperCase() + "]\n" + content);
            }
        }
        var blocks = new ArrayList<String>();
        if (!system.isEmpty()) blocks.add(String.join("\n\n", system));
        if (!tools.isEmpty()) blocks.add(toolPrompt(tools));
        if (!conversation.isEmpty()) blocks.add(String.join("\n\n", conversation));
        return String.join("\n\n", blocks);
    }

    private String toolPrompt(List<MimoTool> tools) {
        var definitions = mapper.createArrayNode();
        for (var tool : tools) {
            definitions.add(mapper.createObjectNode()
                .put("name", tool.name())
                .put("description", tool.description())
                .set("parameters", tool.parameters()));
        }
        return "You can call the functions below. Their JSON Schemas are authoritative.\n<tools>"
            + mapper.writeValueAsString(definitions) + "</tools>\n"
            + "When calling functions, output only <|MiMoML|tool_calls> blocks with "
            + "<|MiMoML|invoke name=\"FUNCTION_NAME\"> and named parameter elements.";
    }

    private List<MimoTool> tools(List<JsonNode> rawTools) {
        var output = new ArrayList<MimoTool>();
        for (var raw : rawTools) {
            var definition = raw.path("function").isObject() ? raw.path("function") : raw;
            var name = definition.path("name").asText("").trim();
            if (name.isBlank()) continue;
            var parameters = definition.has("parameters")
                ? definition.path("parameters").deepCopy()
                : mapper.createObjectNode().put("type", "object")
                    .set("properties", mapper.createObjectNode());
            output.add(new MimoTool(name, definition.path("description").asText(""), parameters));
        }
        return List.copyOf(output);
    }

    private String content(JsonNode value, List<MimoMediaSource> media) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var parts = new ArrayList<String>();
        for (var part : value) {
            if (part.isTextual()) parts.add(part.asText());
            else if (part.isObject()) {
                var type = part.path("type").asText("");
                if (List.of("text", "input_text", "output_text").contains(type)) {
                    parts.add(part.path("text").asText(""));
                } else if (List.of("image_url", "input_image").contains(type)) {
                    var image = part.path("image_url");
                    var dataUrl = image.isTextual() ? image.asText("")
                        : image.path("url").asText("");
                    media.add(new MimoMediaSource("image", dataUrl, null));
                } else {
                    throw new IllegalArgumentException(
                        "unsupported MiMo content block type: " + type);
                }
            }
        }
        return String.join("\n", parts);
    }

    private boolean thinking(CanonicalRequest request) {
        var option = request.providerOptions().get("thinking");
        if (option instanceof Boolean value) return value;
        if (request.rawRequest().path("thinking").isBoolean()) {
            return request.rawRequest().path("thinking").asBoolean();
        }
        var effort = String.valueOf(request.reasoning().getOrDefault("effort",
            request.rawRequest().path("reasoning_effort").asText(""))).toLowerCase();
        return !effort.isBlank() && !List.of("none", "minimal").contains(effort);
    }

    private double number(CanonicalRequest request, String field, double fallback) {
        var value = request.generation().get(field);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private String text(Object value, String fallback) {
        var result = value == null ? "" : String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private String compactUuid() { return UUID.randomUUID().toString().replace("-", ""); }
}
