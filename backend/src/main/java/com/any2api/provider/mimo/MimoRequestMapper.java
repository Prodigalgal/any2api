package com.any2api.provider.mimo;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.List;
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
        request.messages().forEach(message -> collectMedia(message.path("content"), media));
        return new MimoPreparedRequest(
            tools, choice.required(), parallel, List.copyOf(media));
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

    private void collectMedia(JsonNode value, List<MimoMediaSource> media) {
        if (!value.isArray()) return;
        for (var part : value) {
            if (part.isObject()) {
                var type = part.path("type").asText("");
                if (List.of("image_url", "input_image").contains(type)) {
                    var image = part.path("image_url");
                    var dataUrl = image.isTextual() ? image.asText("")
                        : image.path("url").asText("");
                    media.add(new MimoMediaSource("image", dataUrl, null));
                } else if (!List.of("text", "input_text", "output_text").contains(type)) {
                    throw new IllegalArgumentException(
                        "unsupported MiMo content block type: " + type);
                }
            }
        }
    }

    private record ToolChoice(boolean required, boolean disabled, String name) {}
}
