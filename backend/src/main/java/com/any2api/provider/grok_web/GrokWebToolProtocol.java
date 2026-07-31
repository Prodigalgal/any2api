package com.any2api.provider.grok_web;

import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class GrokWebToolProtocol {
    private static final int MAX_FUNCTIONS = 128;
    private static final int MAX_DESCRIPTION_BYTES = 16 << 10;
    private static final int MAX_CAPTURE_BYTES = 1 << 20;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ROOT = Pattern.compile(
        "(?is)<tool_calls\\s*>(.*?)</tool_calls\\s*>");
    private static final Pattern CALL = Pattern.compile(
        "(?is)<tool_call\\s*>(.*?)</tool_call\\s*>");
    private static final Pattern TOOL_NAME = Pattern.compile(
        "(?is)<tool_name\\s*>(.*?)</tool_name\\s*>");
    private static final Pattern PARAMETERS = Pattern.compile(
        "(?is)<parameters\\s*>(.*?)</parameters\\s*>");
    private static final String TOOL_PREFIX = "<tool_calls";

    private final ObjectMapper mapper;

    GrokWebToolProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Configuration parse(CanonicalRequest request) {
        var functions = new ArrayList<FunctionDefinition>();
        var names = new LinkedHashSet<String>();
        var hostedWebSearch = false;
        if (request.tools().size() > MAX_FUNCTIONS) {
            throw new IllegalArgumentException(
                "Grok Web supports at most " + MAX_FUNCTIONS + " tools");
        }
        for (var tool : request.tools()) {
            var type = tool.path("type").asText("").trim().toLowerCase();
            if ("web_search".equals(type) || "web_search_preview".equals(type)) {
                hostedWebSearch = true;
                continue;
            }
            if (!"function".equals(type)) {
                throw new IllegalArgumentException(
                    "Grok Web does not support tools.type=" + type);
            }
            var source = tool.path("function").isObject() ? tool.path("function") : tool;
            var name = source.path("name").asText("").trim();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "function tool name must contain 1 to 64 safe characters");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate function tool: " + name);
            }
            var description = source.path("description").asText("").trim();
            if (description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_DESCRIPTION_BYTES) {
                throw new IllegalArgumentException("function tool description is too long: " + name);
            }
            var parameters = source.path("parameters");
            if (parameters.isMissingNode() || parameters.isNull()) {
                parameters = mapper.createObjectNode()
                    .put("type", "object")
                    .set("properties", mapper.createObjectNode());
            }
            if (!parameters.isObject()) {
                throw new IllegalArgumentException(
                    "function tool parameters must be a JSON object: " + name);
            }
            functions.add(new FunctionDefinition(
                name, description, mapper.writeValueAsString(parameters)));
        }
        var choice = choice(request.rawRequest().path("tool_choice"), names);
        if ((choice.mode() == ChoiceMode.REQUIRED || !choice.forcedName().isBlank())
            && functions.isEmpty() && !hostedWebSearch) {
            throw new IllegalArgumentException(
                "tool_choice requires a tool but no supported tools were declared");
        }
        return new Configuration(
            List.copyOf(functions), Set.copyOf(names), hostedWebSearch,
            choice.mode(), choice.forcedName());
    }

    String inject(String prompt, Configuration configuration) {
        if (configuration.functions().isEmpty() || configuration.mode() == ChoiceMode.NONE) {
            return prompt;
        }
        var definitions = new StringBuilder();
        for (var function : configuration.functions()) {
            if (!definitions.isEmpty()) definitions.append("\n\n");
            definitions.append("Tool: ").append(function.name());
            if (!function.description().isBlank()) {
                definitions.append("\nDescription: ").append(function.description());
            }
            definitions.append("\nParameters: ").append(function.parameters());
        }
        var instruction = "Call a tool when it is clearly needed. Otherwise respond in plain text.";
        if (!configuration.forcedName().isBlank()) {
            instruction = "You MUST call the tool named \"" + configuration.forcedName()
                + "\" and must not write a plain-text reply.";
        } else if (configuration.mode() == ChoiceMode.REQUIRED
            && !configuration.hostedWebSearch()) {
            instruction = "You MUST call at least one available tool and must not write a plain-text reply.";
        }
        return """
            [system]
            You have access to the following tools.

            AVAILABLE TOOLS:
            %s

            TOOL CALL FORMAT - follow these rules exactly:
            - When calling a tool, output only the XML block below, with no text before or after it.
            - <parameters> must contain one valid JSON object.
            - Put multiple calls inside one <tool_calls> element.
            - Do not use Markdown code fences.

            <tool_calls>
              <tool_call>
                <tool_name>TOOL_NAME</tool_name>
                <parameters>{"key":"value"}</parameters>
              </tool_call>
            </tool_calls>

            WHEN TO CALL: %s

            %s
            """.formatted(definitions, instruction, prompt).trim();
    }

    String history(JsonNode message) {
        var type = message.path("type").asText("").trim().toLowerCase();
        if ("function_call".equals(type)) {
            return functionCallXml(
                message.path("name").asText(""), message.path("arguments").asText("{}"));
        }
        if ("function_call_output".equals(type)) {
            return "[tool result for " + message.path("call_id").asText("") + "]\n"
                + scalarText(message.path("output"));
        }
        var text = "";
        if (message.path("tool_calls").isArray()) {
            text = toolCallsXml(message.path("tool_calls"));
        }
        if (!message.path("tool_call_id").asText("").isBlank()) {
            return "Tool result (" + message.path("tool_call_id").asText() + "): "
                + scalarText(message.path("content"));
        }
        return text;
    }

    StreamSieve sieve(Configuration configuration) {
        return configuration.functions().isEmpty() || configuration.mode() == ChoiceMode.NONE
            ? null : new StreamSieve(configuration.names());
    }

    private Choice choice(JsonNode raw, Set<String> names) {
        if (raw.isMissingNode() || raw.isNull()) return new Choice(ChoiceMode.AUTO, "");
        if (raw.isTextual()) {
            return switch (raw.asText("").trim().toLowerCase()) {
                case "auto" -> new Choice(ChoiceMode.AUTO, "");
                case "none" -> new Choice(ChoiceMode.NONE, "");
                case "required" -> new Choice(ChoiceMode.REQUIRED, "");
                default -> throw new IllegalArgumentException(
                    "tool_choice must be auto, none, required, or a function object");
            };
        }
        if (!raw.isObject()) throw new IllegalArgumentException("tool_choice has an invalid shape");
        var type = raw.path("type").asText("").trim().toLowerCase();
        if (Set.of("auto", "none", "required").contains(type)) {
            return new Choice(ChoiceMode.valueOf(type.toUpperCase()), "");
        }
        if (!"function".equals(type)) {
            throw new IllegalArgumentException("Grok Web does not support tool_choice.type=" + type);
        }
        var source = raw.path("function").isObject() ? raw.path("function") : raw;
        var name = source.path("name").asText("").trim();
        if (!NAME.matcher(name).matches() || !names.contains(name)) {
            throw new IllegalArgumentException("tool_choice references an undeclared function");
        }
        return new Choice(ChoiceMode.REQUIRED, name);
    }

    private String toolCallsXml(JsonNode calls) {
        var value = new StringBuilder("<tool_calls>");
        for (var call : calls) {
            var function = call.path("function");
            if (!function.isObject()) continue;
            var xml = functionCallXml(
                function.path("name").asText(""), function.path("arguments").asText("{}"));
            var start = xml.indexOf("<tool_call>");
            var end = xml.lastIndexOf("</tool_call>");
            if (start >= 0 && end > start) {
                value.append('\n').append(xml, start, end + "</tool_call>".length());
            }
        }
        return value.append("\n</tool_calls>").toString();
    }

    private String functionCallXml(String name, String arguments) {
        if (!NAME.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("function_call name is invalid");
        }
        var normalized = normalizeArguments(arguments);
        return "<tool_calls>\n  <tool_call>\n    <tool_name>" + name.trim()
            + "</tool_name>\n    <parameters>" + normalized
            + "</parameters>\n  </tool_call>\n</tool_calls>";
    }

    private String scalarText(JsonNode value) {
        if (value.isTextual()) return value.asText();
        return value.isMissingNode() || value.isNull() ? "" : mapper.writeValueAsString(value);
    }

    private String normalizeArguments(String raw) {
        try {
            var value = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (!value.isObject()) throw new IllegalArgumentException(
                "function arguments must be a JSON object");
            return mapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("function arguments must be valid JSON", error);
        }
    }

    private List<ToolCall> parseCalls(String raw, Set<String> available) {
        var root = ROOT.matcher(raw);
        if (!root.find()) return List.of();
        var calls = new ArrayList<ToolCall>();
        var matcher = CALL.matcher(root.group(1));
        while (matcher.find() && calls.size() < MAX_FUNCTIONS) {
            var body = matcher.group(1);
            var nameMatch = TOOL_NAME.matcher(body);
            if (!nameMatch.find()) continue;
            var name = unescape(nameMatch.group(1).trim());
            if (!available.contains(name)) continue;
            var parameters = PARAMETERS.matcher(body);
            var arguments = parameters.find() ? unescape(parameters.group(1).trim()) : "{}";
            try {
                arguments = normalizeArguments(arguments);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            calls.add(new ToolCall(
                "call_" + UUID.randomUUID().toString().replace("-", ""), name, arguments));
        }
        return List.copyOf(calls);
    }

    private static String unescape(String value) {
        return value.replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    enum ChoiceMode { AUTO, NONE, REQUIRED }

    record FunctionDefinition(String name, String description, String parameters) {}

    record Configuration(
        List<FunctionDefinition> functions,
        Set<String> names,
        boolean hostedWebSearch,
        ChoiceMode mode,
        String forcedName
    ) {}

    record ToolCall(String id, String name, String arguments) {}

    record SieveResult(String safeText, List<ToolCall> calls) {}

    private record Choice(ChoiceMode mode, String forcedName) {}

    final class StreamSieve {
        private final Set<String> available;
        private String buffer = "";
        private boolean capturing;
        private boolean done;

        private StreamSieve(Set<String> available) {
            this.available = available;
        }

        SieveResult feed(String chunk) {
            if (done || chunk == null || chunk.isEmpty()) return new SieveResult("", List.of());
            var combined = buffer + chunk;
            buffer = "";
            var safe = "";
            if (!capturing) {
                var index = combined.toLowerCase().indexOf(TOOL_PREFIX);
                if (index < 0) {
                    var split = splitPrefix(combined);
                    buffer = split.pending();
                    return new SieveResult(split.safe(), List.of());
                }
                capturing = true;
                safe = combined.substring(0, index);
                buffer = combined.substring(index);
            } else {
                buffer = combined;
            }
            if (buffer.length() > MAX_CAPTURE_BYTES) {
                throw new IllegalArgumentException("Grok Web tool call exceeded 1 MiB");
            }
            var end = buffer.toLowerCase().indexOf("</tool_calls>");
            if (end < 0) return new SieveResult(safe, List.of());
            end += "</tool_calls>".length();
            var raw = buffer.substring(0, end);
            var remainder = buffer.substring(end);
            buffer = "";
            capturing = false;
            var calls = parseCalls(raw, available);
            if (calls.isEmpty()) return new SieveResult(safe + raw + remainder, List.of());
            done = true;
            return new SieveResult(safe, calls);
        }

        SieveResult flush() {
            if (done || buffer.isEmpty()) return new SieveResult("", List.of());
            var raw = buffer;
            buffer = "";
            var calls = parseCalls(raw, available);
            if (!calls.isEmpty()) {
                done = true;
                return new SieveResult("", calls);
            }
            return new SieveResult(raw, List.of());
        }

        private Split splitPrefix(String value) {
            var lower = value.toLowerCase();
            for (var size = Math.min(TOOL_PREFIX.length() - 1, lower.length()); size > 0; size--) {
                if (lower.endsWith(TOOL_PREFIX.substring(0, size))) {
                    return new Split(
                        value.substring(0, value.length() - size),
                        value.substring(value.length() - size));
                }
            }
            return new Split(value, "");
        }
    }

    private record Split(String safe, String pending) {}
}
