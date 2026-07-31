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
    private static final int MAX_TOOLS = 128;
    private static final java.util.regex.Pattern TOOL_NAME =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private final ObjectMapper mapper;

    MimoRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    MimoPreparedRequest prepare(CanonicalRequest request) {
        var tools = tools(request.tools());
        var choice = toolChoice(request.rawRequest().path("tool_choice"), tools);
        if (tools.isEmpty() && choice.required()) {
            throw new IllegalArgumentException("tool_choice requires at least one function tool");
        }
        if (choice.disabled()) tools = List.of();
        else if (!choice.name().isBlank()) {
            var requiredName = choice.name();
            tools = tools.stream().filter(tool -> tool.name().equals(requiredName)).toList();
        }
        var parallel = !request.rawRequest().path("parallel_tool_calls").isBoolean()
            || request.rawRequest().path("parallel_tool_calls").asBoolean();
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
            .put("query", messages(request.messages(), tools, media, parallel))
            .set("modelConfig", modelConfig);
        body.set("multiMedias", mapper.createArrayNode());
        body.set("attachments", mapper.createArrayNode());
        return new MimoPreparedRequest(
            body, tools, choice.required(), parallel, List.copyOf(media));
    }

    private String messages(
        List<JsonNode> messages,
        List<MimoTool> tools,
        List<MimoMediaSource> media,
        boolean parallel
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
        if (!tools.isEmpty()) blocks.add(toolPrompt(tools, parallel));
        if (!conversation.isEmpty()) blocks.add(String.join("\n\n", conversation));
        return String.join("\n\n", blocks);
    }

    private String toolPrompt(List<MimoTool> tools, boolean parallel) {
        var definitions = mapper.createArrayNode();
        for (var tool : tools) {
            definitions.add(mapper.createObjectNode()
                .put("name", tool.name())
                .put("description", tool.description())
                .set("parameters", tool.parameters()));
        }
        return "You can call the functions below. Their JSON Schemas are authoritative.\n<tools>"
            + mapper.writeValueAsString(definitions) + "</tools>\n"
            + "Parallel calls allowed: " + parallel + ".\n"
            + "When calling functions, output only <|MiMoML|tool_calls> blocks with "
            + "<|MiMoML|invoke name=\"FUNCTION_NAME\"> and named parameter elements.";
    }

    private List<MimoTool> tools(List<JsonNode> rawTools) {
        if (rawTools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("MiMo supports at most " + MAX_TOOLS + " tools");
        }
        var output = new ArrayList<MimoTool>();
        var names = new java.util.HashSet<String>();
        for (var raw : rawTools) {
            if (!"function".equals(raw.path("type").asText("function"))) {
                throw new IllegalArgumentException("MiMo supports only function tools");
            }
            var definition = raw.path("function").isObject() ? raw.path("function") : raw;
            var name = definition.path("name").asText("").trim();
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("function tool name is invalid");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate function tool: " + name);
            }
            if (definition.path("strict").asBoolean(false)) {
                throw new IllegalArgumentException(
                    "MiMo emulated tools do not support strict=true");
            }
            var parameters = definition.has("parameters")
                ? definition.path("parameters").deepCopy()
                : mapper.createObjectNode().put("type", "object")
                    .set("properties", mapper.createObjectNode());
            output.add(new MimoTool(name, definition.path("description").asText(""), parameters));
        }
        return List.copyOf(output);
    }

    private ToolChoice toolChoice(JsonNode raw, List<MimoTool> tools) {
        if (raw.isMissingNode() || raw.isNull()) return new ToolChoice(false, false, "");
        if (raw.isTextual()) {
            return switch (raw.asText().toLowerCase()) {
                case "auto" -> new ToolChoice(false, false, "");
                case "none" -> new ToolChoice(false, true, "");
                case "required", "any" -> new ToolChoice(true, false, "");
                default -> throw new IllegalArgumentException(
                    "tool_choice must be auto, none, required, any, or a function object");
            };
        }
        if (!raw.isObject() || !"function".equals(raw.path("type").asText(""))) {
            throw new IllegalArgumentException("tool_choice has an invalid shape");
        }
        var name = raw.path("function").path("name").asText(raw.path("name").asText(""));
        if (tools.stream().noneMatch(tool -> tool.name().equals(name))) {
            throw new IllegalArgumentException("tool_choice references an undeclared function");
        }
        return new ToolChoice(true, false, name);
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

    private record ToolChoice(boolean required, boolean disabled, String name) {}
}
