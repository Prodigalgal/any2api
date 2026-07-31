package com.any2api.provider.longcat;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class LongcatToolProtocol {
    private static final int MAX_TOOLS = 128;
    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private final ObjectMapper mapper;

    LongcatToolProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Plan plan(CanonicalRequest request) {
        var tools = normalize(request.tools());
        var choice = choice(request.rawRequest().path("tool_choice"), tools);
        if (tools.isEmpty() && choice.required()) {
            throw new IllegalArgumentException("tool_choice requires at least one function tool");
        }
        var parallel = !request.rawRequest().path("parallel_tool_calls").isBoolean()
            || request.rawRequest().path("parallel_tool_calls").asBoolean();
        return new Plan(tools, choice, parallel);
    }

    String appendContract(String prompt, Plan plan) {
        if (!plan.enabled()) return prompt;
        var definitions = mapper.createArrayNode();
        for (var tool : plan.tools()) {
            definitions.add(mapper.createObjectNode()
                .put("name", tool.name())
                .put("description", tool.description())
                .set("parameters", tool.parameters().deepCopy()));
        }
        var contract = """
            [Tool calling contract]
            Available tools: %s
            Tool choice: %s. Parallel calls allowed: %s.
            When a tool is needed, output only this JSON object and no prose:
            {"tool_calls":[{"name":"tool_name","arguments":{}}]}
            When no tool is needed, answer normally without a tool_calls object.
            """.formatted(mapper.writeValueAsString(definitions), plan.choice().label(),
                plan.parallel()).trim();
        return prompt.isBlank() ? contract : prompt.trim() + "\n\n" + contract;
    }

    List<ToolCall> parse(String text, Plan plan) {
        if (!plan.enabled()) return List.of();
        var allowed = plan.choice().named()
            ? java.util.Set.of(plan.choice().label())
            : plan.tools().stream().map(Tool::name).collect(
                java.util.stream.Collectors.toSet());
        for (var candidate : candidates(text)) {
            try {
                var value = mapper.readTree(candidate);
                var calls = value.isArray() ? value
                    : value.path("tool_calls").isArray() ? value.path("tool_calls")
                    : value.has("function_call")
                        ? mapper.createArrayNode().add(value.path("function_call")) : null;
                if (calls == null) continue;
                var output = new ArrayList<ToolCall>();
                for (var call : calls) {
                    var function = call.path("function").isObject()
                        ? call.path("function") : call;
                    var name = function.path("name").asText("").trim();
                    if (!allowed.contains(name)) continue;
                    var arguments = function.path("arguments");
                    if (arguments.isTextual()) {
                        try { arguments = mapper.readTree(arguments.asText()); }
                        catch (RuntimeException ignored) {
                            throw new IllegalArgumentException(
                                "LongCat emitted invalid JSON tool arguments for " + name);
                        }
                    }
                    if (!arguments.isObject()) {
                        throw new IllegalArgumentException(
                            "LongCat emitted non-object tool arguments for " + name);
                    }
                    var id = call.path("id").asText("").trim();
                    if (id.isBlank()) id = "call_" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 24);
                    output.add(new ToolCall(id, name,
                        mapper.writeValueAsString(arguments)));
                }
                if (!output.isEmpty()) return List.copyOf(output);
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (RuntimeException ignored) {
                // Try the next bounded JSON candidate.
            }
        }
        return List.of();
    }

    private List<Tool> normalize(List<JsonNode> rawTools) {
        if (rawTools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("LongCat supports at most " + MAX_TOOLS + " tools");
        }
        var output = new ArrayList<Tool>();
        var names = new java.util.HashSet<String>();
        for (var raw : rawTools) {
            var type = raw.path("type").asText("function");
            if (!"function".equals(type)) {
                throw new IllegalArgumentException("LongCat supports only function tools");
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
                    "LongCat emulated tools do not support strict=true");
            }
            var parameters = definition.path("parameters").isObject()
                ? definition.path("parameters").deepCopy()
                : mapper.createObjectNode().put("type", "object")
                    .set("properties", mapper.createObjectNode());
            output.add(new Tool(name, definition.path("description").asText(""), parameters));
        }
        return List.copyOf(output);
    }

    private Choice choice(JsonNode raw, List<Tool> tools) {
        if (raw.isMissingNode() || raw.isNull()) {
            return new Choice("auto", false, false, false);
        }
        if (raw.isTextual()) {
            return switch (raw.asText().toLowerCase()) {
                case "auto" -> new Choice("auto", false, false, false);
                case "none" -> new Choice("none", false, true, false);
                case "required", "any" -> new Choice("required", true, false, false);
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
        return new Choice(name, true, false, true);
    }

    private List<String> candidates(String text) {
        var raw = text == null ? "" : text.trim();
        var output = new LinkedHashSet<String>();
        if (!raw.isBlank()) output.add(raw);
        var fenced = java.util.regex.Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(raw);
        while (fenced.find()) output.add(fenced.group(1).trim());
        var tagged = java.util.regex.Pattern.compile(
            "<tool_calls>\\s*([\\s\\S]*?)\\s*</tool_calls>",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
        if (tagged.find()) output.add(tagged.group(1).trim());
        var start = raw.indexOf('{');
        var end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) output.add(raw.substring(start, end + 1));
        return List.copyOf(output);
    }

    record Plan(List<Tool> tools, Choice choice, boolean parallel) {
        boolean enabled() { return !tools.isEmpty() && !choice.disabled(); }
        boolean required() { return choice.required(); }
    }

    record Tool(String name, String description, JsonNode parameters) {}
    record ToolCall(String id, String name, String arguments) {}
    record Choice(String label, boolean required, boolean disabled, boolean named) {}
}
