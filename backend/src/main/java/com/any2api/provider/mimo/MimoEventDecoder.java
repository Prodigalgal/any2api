package com.any2api.provider.mimo;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

final class MimoEventDecoder {
    private static final int MAX_TOOL_CAPTURE_CHARACTERS = 1 << 20;
    private static final int MAX_PARAMETER_TYPE_DEPTH = 32;
    private static final int MAX_PARAMETER_TYPE_NODES = 2048;
    private static final String LENGTH_REJECTION = "Sorry, the text you sent is too long! "
        + "I suggest you simplify the content appropriately or send it in parts. "
        + "Thank you for your understanding.";
    private static final Set<String> INTERNAL_TOKENS = Set.of(
        "webSearch", "getTime", "getTimeInfo", "sessionSearch", "imageSearch",
        "fileSearch", "getLocation", "webExtract", "getWeather", "calculator");
    private static final Set<String> MESSAGE_EVENTS = Set.of(
        "message", "text", "usage", "finish", "error", "sensitive_query", "sensitive_title");
    private static final Set<String> CONTROL_EVENTS = Set.of(
        "dialogId", "tip_ratio", "tip_truncate", "web_search", "doc");
    private static final Pattern INVOKE = Pattern.compile(
        "<\\|MiMoML\\|invoke\\s+name=[\\\"']([^\\\"']+)[\\\"']\\s*>([\\s\\S]*?)</\\|MiMoML\\|invoke>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMETER = Pattern.compile(
        "<\\|MiMoML\\|parameter\\s+name=[\\\"']([^\\\"']+)[\\\"']\\s*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?</\\|MiMoML\\|parameter>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_CALL = Pattern.compile(
        "<tool_call>([\\s\\S]*?)</tool_call>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_FUNCTION = Pattern.compile(
        "<function=([\\w.-]+)>([\\s\\S]*?)</function>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_PARAMETER = Pattern.compile(
        "<parameter=([\\w.-]+)>([\\s\\S]*?)</parameter>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGGED_FUNCTION = Pattern.compile(
        "<function_calls?>([\\s\\S]*?)</function_calls?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN_CALL = Pattern.compile(
        "TOOL_CALL:\\s*([\\w.-]+)\\s*\\(([^\\n]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THOUGHT = Pattern.compile(
        "\\G\\s*<think>[\\s\\S]*?(?:</think>|$)", Pattern.CASE_INSENSITIVE);

    private final String requestId;
    private String inputParameter = "input";
    private final List<MimoTool> tools;
    private final boolean toolRequired;
    private final boolean parallelToolCalls;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder buffer = new StringBuilder();
    private final Set<String> emittedCalls = new HashSet<>();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean reasoning;
    private boolean emittedAnswer;
    private boolean usageEmitted;

    MimoEventDecoder(
        String requestId,
        List<MimoTool> tools,
        boolean toolRequired,
        boolean parallelToolCalls
    ) {
        this.requestId = requestId;
        this.tools = tools;
        this.toolRequired = toolRequired;
        this.parallelToolCalls = parallelToolCalls;
    }

    MimoEventDecoder(String requestId, List<MimoTool> tools, boolean toolRequired,
        boolean parallelToolCalls, String inputParameter) {
        this(requestId, tools, toolRequired, parallelToolCalls);
        this.inputParameter = inputParameter;
    }

    List<CanonicalEvent> decode(String data) {
        return decode("", data);
    }

    List<CanonicalEvent> decode(String eventName, String data) {
        if (completed) return List.of();
        if ("[DONE]".equals(data == null ? "" : data.trim())) return finish();
        var output = start();
        if (eventName != null && !eventName.isBlank() && !MESSAGE_EVENTS.contains(eventName)) {
            return output;
        }
        if (data == null || data.isBlank()) {
            if ("finish".equals(eventName)) output.addAll(finish());
            return output;
        }
        try {
            var event = mapper.readTree(data);
            var messageType = event.path("messageType").asText("");
            var kind = messageType.isBlank() ? eventName : messageType;
            if (kind == null || kind.isBlank()) kind = event.path("type").asText("");
            if (CONTROL_EVENTS.contains(kind)) return output;
            var text = event.path("content").asText("");
            if (isMaintenanceNotification(text)) {
                output.add(fail("upstream_maintenance",
                    "MiMo Studio requires a page refresh before generating a response", true));
                return output;
            }
            if ("error".equals(kind) || !event.path("error").asText("").isBlank()
                || event.path("error").isObject() && !event.path("error").isEmpty()) {
                var errorText = event.path("error").isObject()
                    ? event.path("error").path("message").asText("")
                    : event.path("error").asText("");
                if (errorText.isBlank()) errorText = text.isBlank() ? "MiMo upstream rejected the request" : text;
                output.add(fail("upstream_error", errorText, true));
                return output;
            }
            if ("sensitive_query".equals(kind) || "sensitive_title".equals(kind)) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(), "content_filter",
                    "MiMo Web rejected the request under its content policy",
                    java.util.Map.of("retryable", false, "status", 400, "param", inputParameter)));
                buffer.setLength(0);
                completed = true;
                return output;
            }
            if ("finish".equals(kind)) {
                emitUsage(event, output);
                output.addAll(finish());
                return output;
            }
            if ("usage".equals(kind)) {
                emitUsage(event, output);
                return output;
            }
            if (event.has("reasoningContent")) {
                var reasoningText = event.path("reasoningContent").asText("");
                if (!reasoningText.isEmpty()) {
                    output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), reasoningText));
                    emitUsage(event, output);
                    return output;
                }
            }
            if (!text.isEmpty()) {
                if (!INTERNAL_TOKENS.contains(text.trim())) {
                    var normalized = text.replace("\0", "");
                    if (!tools.isEmpty() && normalized.length() > MAX_TOOL_CAPTURE_CHARACTERS - buffer.length()) {
                        output.add(new CanonicalEvent.Failed(1, requestId, next(), "tool_call_generation_failed",
                            "MiMo tool output exceeds capture limit", java.util.Map.of("retryable", false)));
                        buffer.setLength(0);
                        completed = true;
                        return output;
                    }
                    buffer.append(normalized);
                    if (tools.isEmpty() && !pendingLengthRejection()) output.addAll(drain(false));
                }
            }
            emitUsage(event, output);
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("MiMo upstream emitted invalid SSE JSON", error);
        }
    }

    private void emitUsage(tools.jackson.databind.JsonNode event, List<CanonicalEvent> output) {
        var usageNode = event.has("usage") ? event.path("usage") : event;
        if (usageNode.has("promptTokens") && !usageEmitted) {
            output.add(new CanonicalEvent.Usage(1, requestId, next(),
                usageNode.path("promptTokens").asLong(), usageNode.path("completionTokens").asLong(), 0));
            usageEmitted = true;
        }
    }

    private CanonicalEvent.Failed fail(String type, String message, boolean retryable) {
        buffer.setLength(0);
        completed = true;
        return new CanonicalEvent.Failed(1, requestId, next(), type, message,
            java.util.Map.of("retryable", retryable));
    }

    private static boolean isMaintenanceNotification(String text) {
        var normalized = text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("mimostudioupdated") && normalized.contains("refreshyourpage")
            || text.contains("平台升级") && text.contains("刷新页面");
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        if (buffer.toString().trim().equals(LENGTH_REJECTION)) {
            output.add(new CanonicalEvent.Failed(1, requestId, next(), "context_length_exceeded",
                "MiMo Web rejected the combined instructions, messages and tool definitions as too long",
                java.util.Map.of("param", inputParameter, "retryable", false, "status", 400)));
            buffer.setLength(0);
            completed = true;
            return output;
        }
        if (tools.isEmpty()) {
            output.addAll(drain(true));
        } else {
            try {
                emitTools(buffer.toString(), output);
            } catch (IllegalArgumentException error) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(), "tool_call_generation_failed",
                    error.getMessage(), java.util.Map.of("retryable", false)));
                buffer.setLength(0);
                completed = true;
                return output;
            }
            if (!parallelToolCalls && emittedCalls.size() > 1) {
                output.removeIf(event -> event instanceof CanonicalEvent.ToolCallStarted
                    || event instanceof CanonicalEvent.ToolArgumentsDelta
                    || event instanceof CanonicalEvent.ToolCallCompleted);
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "tool_call_generation_failed",
                    "MiMo produced parallel calls while parallel_tool_calls=false",
                    java.util.Map.of()));
                completed = true;
                return output;
            }
            if (emittedCalls.isEmpty()) {
                if (looksLikeToolSyntax(buffer.toString()) || toolRequired) {
                    output.add(new CanonicalEvent.Failed(1, requestId, next(),
                        "tool_call_generation_failed",
                        toolRequired
                            ? "MiMo did not produce the required function tool call"
                            : "MiMo emitted tool syntax that could not be parsed",
                        java.util.Map.of()));
                    completed = true;
                    return output;
                }
                output.addAll(drainText(buffer.toString()));
            }
            buffer.setLength(0);
        }
        if (emittedCalls.isEmpty() && !emittedAnswer) {
            output.add(new CanonicalEvent.Failed(1, requestId, next(),
                "empty_model_response", "MiMo returned no model output", java.util.Map.of()));
            completed = true;
            return output;
        }
        output.add(new CanonicalEvent.Completed(1, requestId, next(),
            emittedCalls.isEmpty() ? "stop" : "tool_calls"));
        completed = true;
        return output;
    }

    private boolean pendingLengthRejection() {
        var received = buffer.toString().strip();
        return !emittedAnswer && !reasoning && LENGTH_REJECTION.startsWith(received);
    }

    private List<CanonicalEvent> drain(boolean flush) {
        var output = new ArrayList<CanonicalEvent>();
        if (!tools.isEmpty()) {
            var source = buffer.toString();
            var marker = source.indexOf("<|MiMoML|tool_calls>");
            if (marker >= 0) {
                emitText(source.substring(0, marker), output);
                var toolSource = source.substring(marker);
                if (toolSource.contains("</|MiMoML|tool_calls>") || flush) {
                    emitTools(toolSource, output);
                    buffer.setLength(0);
                } else {
                    buffer.delete(0, marker);
                }
                return output;
            }
        }
        while (!buffer.isEmpty()) {
            var source = buffer.toString();
            var hit = source.indexOf(reasoning ? "</think>" : "<think>");
            if (hit < 0) {
                var hold = flush ? 0 : partialControlSuffix(source);
                var length = source.length() - hold;
                if (length > 0) {
                    emitText(source.substring(0, length), output);
                    buffer.delete(0, length);
                }
                break;
            }
            emitText(source.substring(0, hit), output);
            var token = reasoning ? "</think>" : "<think>";
            buffer.delete(0, hit + token.length());
            reasoning = !reasoning;
        }
        return output;
    }

    private void emitText(String value, List<CanonicalEvent> output) {
        if (value.isEmpty()) return;
        if (reasoning) output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), value));
        else {
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), value));
            if (!value.isBlank()) emittedAnswer = true;
        }
    }

    private void emitTools(String source, List<CanonicalEvent> output) {
        var calls = new ArrayList<ParsedCall>();
        var unparsed = withoutReasoning(source);
        var matcher = INVOKE.matcher(unparsed);
        while (matcher.find()) {
            var name = matcher.group(1).trim();
            name = resolveName(name);
            if (name == null) throw new IllegalArgumentException("MiMo emitted an undeclared function tool");
            var arguments = mapper.createObjectNode();
            var parameter = PARAMETER.matcher(matcher.group(2));
            while (parameter.find()) {
                putAuto(arguments, name, parameter.group(1), parameter.group(2));
            }
            calls.add(new ParsedCall(name, arguments, new CallGroup(ToolSyntax.MIMOML, 0)));
            maskRange(unparsed, matcher.start(), matcher.end());
        }
        var xml = XML_CALL.matcher(unparsed);
        while (xml.find()) {
            var function = XML_FUNCTION.matcher(xml.group(1));
            if (!function.find()) continue;
            var name = resolveName(function.group(1));
            if (name == null) throw new IllegalArgumentException("MiMo emitted an undeclared function tool");
            var arguments = mapper.createObjectNode();
            var parameter = XML_PARAMETER.matcher(function.group(2));
            while (parameter.find()) putAuto(arguments, name, parameter.group(1), parameter.group(2));
            calls.add(new ParsedCall(name, arguments, new CallGroup(ToolSyntax.XML, 0)));
            maskRange(unparsed, xml.start(), xml.end());
        }
        var tagged = TAGGED_FUNCTION.matcher(unparsed);
        while (tagged.find()) {
            addJsonCalls(tagged.group(1), calls, new CallGroup(ToolSyntax.TAGGED_JSON, 0));
            maskRange(unparsed, tagged.start(), tagged.end());
        }
        var plain = PLAIN_CALL.matcher(unparsed);
        while (plain.find()) {
            var name = resolveName(plain.group(1));
            if (name == null) throw new IllegalArgumentException("MiMo emitted an undeclared function tool");
            var arguments = mapper.createObjectNode();
            var raw = plain.group(2).trim();
            try {
                var parsed = mapper.readTree(raw);
                if (parsed.isObject()) arguments = (tools.jackson.databind.node.ObjectNode) parsed;
            } catch (RuntimeException ignored) {
                for (var pair : plainArgumentPairs(raw)) {
                    var parts = pair.split("=", 2);
                    if (parts.length == 2) putAuto(arguments, name, parts[0], parts[1]);
                }
            }
            calls.add(new ParsedCall(name, arguments, new CallGroup(ToolSyntax.PLAIN, 0)));
            maskRange(unparsed, plain.start(), plain.end());
        }
        var candidateIndex = 0;
        for (var candidate : jsonCandidates(unparsed.toString())) {
            addJsonCalls(candidate, calls, new CallGroup(ToolSyntax.JSON, candidateIndex++));
        }
        var occurrences = new java.util.HashMap<CallGroup, java.util.Map<String, Integer>>();
        for (var call : calls) {
            var serialized = mapper.writeValueAsString(call.arguments());
            var signature = call.name() + ":" + serialized;
            var ordinal = occurrences.computeIfAbsent(call.group(), ignored -> new java.util.HashMap<>())
                .merge(signature, 1, Integer::sum);
            // Multiple syntaxes can describe one call; equal calls within a batch are independent invocations.
            if (!emittedCalls.add(signature + "\0" + ordinal)) continue;
            var id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            output.add(new CanonicalEvent.ToolCallStarted(1, requestId, next(), id, call.name()));
            output.add(new CanonicalEvent.ToolArgumentsDelta(1, requestId, next(), id, serialized));
            output.add(new CanonicalEvent.ToolCallCompleted(1, requestId, next(), id, serialized));
        }
    }

    private static void maskRange(StringBuilder source, int from, int to) {
        // Argument content and reasoning are data; fallback parsers must not execute embedded examples.
        for (var index = from; index < to; index++) source.setCharAt(index, ' ');
    }

    private static StringBuilder withoutReasoning(String source) {
        // MiMo thoughts precede visible output. Tags inside tool values remain literal data.
        var visible = new StringBuilder(source);
        var thought = THOUGHT.matcher(source);
        while (thought.find()) maskRange(visible, thought.start(), thought.end());
        return visible;
    }

    private static List<String> plainArgumentPairs(String source) {
        var pairs = new ArrayList<String>();
        var start = 0;
        var nesting = 0;
        var quoted = false;
        var escaped = false;
        for (var index = 0; index < source.length(); index++) {
            var character = source.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '{' || character == '[') nesting++;
            else if (character == '}' || character == ']') nesting--;
            else if (character == ',' && nesting == 0) {
                pairs.add(source.substring(start, index));
                start = index + 1;
            }
        }
        pairs.add(source.substring(start));
        return pairs;
    }

    private List<CanonicalEvent> drainText(String source) {
        var output = new ArrayList<CanonicalEvent>();
        var position = 0;
        var inReasoning = false;
        while (position < source.length()) {
            var token = inReasoning ? "</think>" : "<think>";
            var hit = source.indexOf(token, position);
            var end = hit < 0 ? source.length() : hit;
            var value = source.substring(position, end);
            if (!value.isEmpty()) {
                if (inReasoning) output.add(new CanonicalEvent.ReasoningDelta(
                    1, requestId, next(), value));
                else output.add(new CanonicalEvent.OutputTextDelta(
                    1, requestId, next(), value));
                if (!inReasoning && !value.isBlank()) emittedAnswer = true;
            }
            if (hit < 0) break;
            position = hit + token.length();
            inReasoning = !inReasoning;
        }
        return output;
    }

    private String resolveName(String raw) {
        var normalized = raw == null ? "" : raw.trim().toLowerCase();
        for (var tool : tools) {
            if (tool.name().equalsIgnoreCase(normalized)) return tool.name();
            var compact = normalized.replace("_", "").replace("-", "");
            if (tool.name().toLowerCase().replace("_", "").replace("-", "")
                .equals(compact)) return tool.name();
        }
        return null;
    }

    private void addJsonCalls(String source, List<ParsedCall> calls, CallGroup group) {
        var allowed = tools.stream().map(MimoTool::name).collect(java.util.stream.Collectors.toSet());
        for (var call : com.any2api.provider.EmulatedToolCallParser.parse(
                mapper, List.of(source.trim()), allowed, this::resolveName, "MiMo")) {
            calls.add(new ParsedCall(call.name(),
                (tools.jackson.databind.node.ObjectNode) mapper.readTree(call.arguments()), group));
        }
    }

    private Set<String> jsonCandidates(String source) {
        var candidates = new LinkedHashSet<String>();
        var raw = source == null ? "" : source.trim();
        if ((raw.startsWith("{") && raw.endsWith("}"))
            || (raw.startsWith("[") && raw.endsWith("]"))) {
            candidates.add(raw);
        }
        var fenced = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(raw);
        while (fenced.find()) candidates.add(fenced.group(1).trim());
        var start = raw.indexOf('{');
        var end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) candidates.add(raw.substring(start, end + 1));
        return candidates;
    }

    private void putAuto(
        tools.jackson.databind.node.ObjectNode target,
        String toolName,
        String name,
        String raw
    ) {
        var parameterName = name.trim();
        var value = raw == null ? "" : raw.trim()
            .replaceFirst("^<!\\[CDATA\\[", "").replaceFirst("]]>$", "").trim();
        tools.jackson.databind.JsonNode parsed;
        try { parsed = mapper.readTree(value); }
        catch (RuntimeException ignored) {
            target.put(parameterName, value);
            return;
        }
        var types = parameterTypes(toolName, parameterName);
        // MiMoML/XML parameters are untyped text; JSON argument objects keep their explicit types.
        if (types.contains("string") && types.stream().allMatch(type -> type.equals("string") || type.equals("null"))
            && (parsed == null || !parsed.isTextual())
            && !(parsed != null && parsed.isNull() && types.contains("null"))) {
            target.put(parameterName, value);
        } else {
            target.set(parameterName, parsed);
        }
    }

    private Set<String> parameterTypes(String toolName, String parameterName) {
        for (var tool : tools) {
            if (tool.name().equals(toolName)) {
                return declaredTypes(tool.parameters().path("properties").path(parameterName), tool.parameters(), 0, new int[1]);
            }
        }
        return Set.of();
    }

    private Set<String> declaredTypes(tools.jackson.databind.JsonNode schema,
        tools.jackson.databind.JsonNode root, int depth, int[] nodes) {
        if (depth > MAX_PARAMETER_TYPE_DEPTH || ++nodes[0] > MAX_PARAMETER_TYPE_NODES || !schema.isObject()) return Set.of();
        if (schema.has("$ref")) {
            var reference = schema.path("$ref").asText("");
            if (!reference.startsWith("#/")) return Set.of();
            return declaredTypes(root.at(reference.substring(1)), root, depth + 1, nodes);
        }
        if (schema.path("anyOf").isArray()) {
            var types = new HashSet<String>();
            for (var branch : schema.path("anyOf")) {
                var branchTypes = declaredTypes(branch, root, depth + 1, nodes);
                if (branchTypes.isEmpty()) return Set.of();
                types.addAll(branchTypes);
            }
            return types;
        }
        var type = schema.path("type");
        if (type.isTextual()) return Set.of(type.asText());
        var types = new HashSet<String>();
        if (type.isArray()) for (var entry : type) types.add(entry.asText());
        return types;
    }

    private boolean looksLikeToolSyntax(String source) {
        var visible = withoutReasoning(source);
        return List.of("<|MiMoML|tool_calls>", "<tool_call>", "<function_call>",
            "<function_calls>", "TOOL_CALL:").stream().anyMatch(marker -> visible.indexOf(marker) >= 0);
    }

    private int partialControlSuffix(String source) {
        var max = 0;
        for (var token : List.of("<think>", "</think>", "<|MiMoML|tool_calls>")) {
            for (var length = 1; length < token.length() && length <= source.length(); length++) {
                if (source.endsWith(token.substring(0, length))) max = Math.max(max, length);
            }
        }
        return max;
    }

    private ArrayList<CanonicalEvent> start() {
        var output = new ArrayList<CanonicalEvent>();
        if (!started) {
            output.add(new CanonicalEvent.ResponseStarted(1, requestId, next(),
                "resp_" + requestId.replace("-", "")));
            started = true;
        }
        return output;
    }

    private long next() { return sequence++; }

    private enum ToolSyntax { MIMOML, XML, TAGGED_JSON, JSON, PLAIN }
    private record CallGroup(ToolSyntax syntax, int candidateIndex) {}
    private record ParsedCall(String name, tools.jackson.databind.node.ObjectNode arguments, CallGroup group) {}
}
