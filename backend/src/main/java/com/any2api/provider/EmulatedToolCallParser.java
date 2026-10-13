package com.any2api.provider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses one complete WEB tool batch before any caller-owned invocation is published. */
public final class EmulatedToolCallParser {
    private EmulatedToolCallParser() {}

    public static List<Call> parse(ObjectMapper mapper, List<String> candidates, Set<String> allowed, String label) {
        return parse(mapper, candidates, allowed, UnaryOperator.identity(), label);
    }

    public static List<Call> parse(ObjectMapper mapper, List<String> candidates, Set<String> allowed,
                                   UnaryOperator<String> nameResolver, String label) {
        for (var candidate : candidates) {
            final JsonNode value;
            try {
                value = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(candidate);
            } catch (RuntimeException ignored) {
                // Prose and wrappers can precede a complete provider-owned JSON candidate.
                continue;
            }
            if (value == null) continue;
            if (value.has("tool_calls") && !value.path("tool_calls").isArray()) {
                throw new IllegalArgumentException(label + " emitted an invalid tool_calls array");
            }
            var batch = batch(value, mapper);
            if (batch == null) continue;
            var output = new ArrayList<Call>();
            var ids = new HashSet<String>();
            for (var call : batch) {
                var function = call.path("function").isObject() ? call.path("function") : call;
                var name = function.path("name").isTextual()
                    ? nameResolver.apply(function.path("name").asText().trim()) : null;
                if (name == null || !allowed.contains(name)) {
                    throw new IllegalArgumentException(label + " emitted an undeclared function tool");
                }
                var arguments = function.has("arguments") ? function.path("arguments") : function.path("parameters");
                if (arguments.isTextual()) {
                    try {
                        arguments = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(arguments.asText());
                    } catch (RuntimeException error) {
                        throw new IllegalArgumentException(label + " emitted invalid JSON tool arguments for " + name);
                    }
                }
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalArgumentException(label + " emitted non-object tool arguments for " + name);
                }
                var id = call.path("id").asText("").trim();
                if (id.isBlank()) id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                if (!ids.add(id)) throw new IllegalArgumentException(label + " emitted duplicate tool call IDs");
                output.add(new Call(id, name, mapper.writeValueAsString(arguments)));
            }
            if (!output.isEmpty()) return List.copyOf(output);
        }
        return List.of();
    }

    private static JsonNode batch(JsonNode value, ObjectMapper mapper) {
        if (value.path("tool_calls").isArray()) return value.path("tool_calls");
        if (value.has("function_call")) return mapper.createArrayNode().add(value.path("function_call"));
        if (isCall(value)) return mapper.createArrayNode().add(value);
        if (value.isArray()) {
            for (var item : value) if (isCall(item)) return value;
        }
        return null;
    }

    private static boolean isCall(JsonNode value) {
        var function = value.path("function").isObject() ? value.path("function") : value;
        return function.isObject() && function.has("name")
            && (function.has("arguments") || function.has("parameters"));
    }

    public record Call(String id, String name, String arguments) {}
}
