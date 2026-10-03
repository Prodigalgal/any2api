package com.any2api.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Maps client tool identities to the function protocol used by existing providers. */
public final class OpenAiToolBridge {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private OpenAiToolBridge() {}

    public record Binding(String upstreamName, String name, String namespace, boolean custom, JsonNode tool) {}

    public static List<Binding> bindings(JsonNode tools) {
        var bindings = new ArrayList<Binding>();
        if (tools.isArray()) {
            for (var tool : tools) {
                if ("namespace".equals(tool.path("type").asText())) {
                    var namespace = requiredName(tool, "name");
                    if (!tool.path("tools").isArray() || tool.path("tools").isEmpty()) {
                        throw OpenAiRequestException.invalid("tools", "namespace requires tools");
                    }
                    for (var nested : tool.path("tools")) add(bindings, nested, namespace);
                } else add(bindings, tool, "");
            }
        }
        var identities = new java.util.HashSet<String>();
        var upstreamNames = new java.util.HashSet<String>();
        for (var binding : bindings) {
            if (!identities.add(binding.namespace() + "/" + binding.name())) {
                throw OpenAiRequestException.invalid("tools", "duplicate tool identity: " + binding.name());
            }
            if (!upstreamNames.add(binding.upstreamName())) {
                throw OpenAiRequestException.invalid("tools", "tool bridge name collision: " + binding.name());
            }
        }
        return List.copyOf(bindings);
    }

    private static void add(List<Binding> bindings, JsonNode tool, String namespace) {
        var type = tool.path("type").asText("function");
        if (!List.of("function", "custom").contains(type)) {
            if (!namespace.isEmpty()) throw OpenAiRequestException.unsupported("tools", "unsupported namespace tool: " + type);
            return; // Hosted tools continue through their existing provider contracts.
        }
        var definition = tool.path("function").isObject() ? tool.path("function") : tool;
        var name = requiredName(definition, "name");
        if (definition.hasNonNull("strict") && !definition.path("strict").isBoolean()) {
            throw OpenAiRequestException.invalid("tools.strict", "strict must be a boolean");
        }
        if (definition.hasNonNull("parameters") && !definition.path("parameters").isObject()) {
            throw OpenAiRequestException.invalid("tools.parameters", "function parameters must be an object schema");
        }
        if (definition.hasNonNull("defer_loading") && !definition.path("defer_loading").isBoolean()) {
            throw OpenAiRequestException.invalid("tools.defer_loading", "defer_loading must be a boolean");
        }
        if (definition.path("defer_loading").asBoolean(false)) {
            throw OpenAiRequestException.unsupported("tools.defer_loading", "deferred tools require tool search support");
        }
        var custom = "custom".equals(type);
        if (custom && definition.hasNonNull("format") && !definition.path("format").isObject()) {
            throw OpenAiRequestException.invalid("tools.format", "custom format must be an object");
        }
        if (custom && definition.path("format").hasNonNull("type")
            && !"text".equals(definition.path("format").path("type").asText())) {
            throw OpenAiRequestException.unsupported("tools.format", "custom grammar is not supported by the function bridge");
        }
        bindings.add(new Binding(upstreamName(name, namespace, custom), name, namespace, custom, definition));
    }

    public static String upstreamName(String name, String namespace, boolean custom) {
        if (namespace.isEmpty() && !custom) return name;
        try {
            var identity = (custom ? "custom:" : "function:") + namespace + ":" + name;
            return "a2a_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static List<JsonNode> functions(JsonNode tools) {
        var bindings = bindings(tools);
        var result = new ArrayList<JsonNode>();
        for (var binding : bindings) {
            var tool = JSON.objectNode().put("type", "function").put("name", binding.upstreamName());
            tool.put("description", binding.tool().path("description").asText("")
                + (binding.custom() ? " Pass the exact free-form tool input in the input string." : ""));
            if (binding.custom()) {
                var schema = JSON.objectNode().put("type", "object").put("additionalProperties", false);
                schema.putObject("properties").putObject("input").put("type", "string");
                schema.putArray("required").add("input");
                tool.set("parameters", schema);
            } else {
                tool.set("parameters", binding.tool().hasNonNull("parameters") ? binding.tool().path("parameters").deepCopy()
                    : emptyFunctionParameters(binding.tool().path("strict").asBoolean(false)));
            }
            if (binding.tool().has("strict")) tool.set("strict", binding.tool().path("strict").deepCopy());
            result.add(tool);
        }
        if (tools.isArray()) for (var tool : tools) {
            if (!List.of("function", "custom", "namespace").contains(tool.path("type").asText("function"))) {
                result.add(tool.deepCopy());
            }
        }
        return List.copyOf(result);
    }

    static ObjectNode emptyFunctionParameters(boolean strict) {
        var parameters = JSON.objectNode().put("type", "object");
        parameters.set("properties", JSON.objectNode());
        if (strict) parameters.put("additionalProperties", false);
        return parameters;
    }

    public static Binding resolve(CanonicalRequest request, String upstreamName) {
        return bindings(request.rawRequest().path("tools")).stream()
            .filter(binding -> binding.upstreamName().equals(upstreamName)).findFirst()
            .orElse(new Binding(upstreamName, upstreamName, "", false, JSON.objectNode()));
    }

    public static CanonicalRequest forProvider(CanonicalRequest request) {
        var raw = (ObjectNode) request.rawRequest().deepCopy();
        var tools = new ArrayList<>(request.tools());
        var generation = new java.util.LinkedHashMap<>(request.generation());
        var choice = raw.path("tool_choice");
        if (choice.isObject() && List.of("allowed_tools", "function", "custom").contains(choice.path("type").asText())) {
            if ("allowed_tools".equals(choice.path("type").asText())) {
                if (!choice.path("tools").isArray() || choice.path("tools").isEmpty()) {
                    throw OpenAiRequestException.invalid("tool_choice", "allowed_tools requires a nonempty tools array");
                }
                var allowed = new java.util.HashSet<String>();
                for (var reference : choice.path("tools")) allowed.add(choiceName(request, reference));
                tools.removeIf(tool -> !allowed.contains(tool.path("name").asText()));
                var mode = choice.path("mode").asText("auto");
                if (!List.of("auto", "required").contains(mode)) {
                    throw OpenAiRequestException.invalid("tool_choice.mode", "allowed_tools mode must be auto or required");
                }
                raw.put("tool_choice", mode);
                generation.put("tool_choice", mode);
            } else {
                var name = choiceName(request, choice);
                raw.set("tool_choice", JSON.objectNode().put("type", "function")
                    .put("name", name));
                generation.put("tool_choice", java.util.Map.of("type", "function", "name", name));
            }
        }
        if (raw.has("tools")) {
            var array = raw.putArray("tools");
            tools.forEach(array::add);
        }
        return new CanonicalRequest(request.requestId(), request.protocol(), request.providerId(), request.model(),
            request.stream(), request.messages(), java.util.Map.copyOf(generation), request.reasoning(), List.copyOf(tools),
            request.providerOptions(), raw);
    }

    private static String choiceName(CanonicalRequest request, JsonNode choice) {
        var type = choice.path("type").asText();
        if (!List.of("function", "custom").contains(type)) {
            throw OpenAiRequestException.unsupported("tool_choice.type", "unsupported tool choice: " + type);
        }
        var name = choice.path("function").path("name").asText(choice.path("name").asText(""));
        var namespace = choice.path("namespace").asText("");
        return bindings(request.rawRequest().path("tools")).stream()
            .filter(binding -> binding.name().equals(name) && binding.namespace().equals(namespace)
                && binding.custom() == "custom".equals(type))
            .findFirst().orElseThrow(() -> OpenAiRequestException.invalid("tool_choice", "tool choice references an undeclared tool"))
            .upstreamName();
    }

    public static String customInput(String arguments, ObjectMapper mapper) {
        try {
            var value = mapper.readTree(arguments).path("input");
            if (!value.isTextual()) throw OpenAiRequestException.invalid("tool.input", "custom tool bridge requires a string input");
            return value.asText();
        } catch (OpenAiRequestException error) {
            throw error;
        } catch (Exception error) {
            throw OpenAiRequestException.invalid("tool.input", "custom tool bridge returned invalid JSON arguments");
        }
    }

    private static String requiredName(JsonNode value, String field) {
        var name = value.path(field).asText("");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw OpenAiRequestException.invalid("tools." + field, "tool name must match [A-Za-z0-9_-]{1,64}");
        }
        return name;
    }
}
