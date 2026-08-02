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
    private static final Set<String> INTERNAL_TOKENS = Set.of(
        "webSearch", "getTime", "getTimeInfo", "sessionSearch", "imageSearch",
        "fileSearch", "getLocation", "webExtract", "getWeather", "calculator");
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

    private final String requestId;
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

    List<CanonicalEvent> decode(String data) {
        var output = start();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return output;
        try {
            var event = mapper.readTree(data);
            if (event.path("type").asText("").equals("text")) {
                var text = event.path("content").asText("");
                if (!text.isBlank() && !INTERNAL_TOKENS.contains(text.trim())) {
                    buffer.append(text.replace("\0", ""));
                    if (tools.isEmpty()) output.addAll(drain(false));
                }
            } else if (event.has("promptTokens") && !usageEmitted) {
                output.add(new CanonicalEvent.Usage(1, requestId, next(),
                    event.path("promptTokens").asLong(), event.path("completionTokens").asLong(), 0));
                usageEmitted = true;
            }
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("MiMo upstream emitted invalid SSE JSON", error);
        }
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        if (tools.isEmpty()) {
            output.addAll(drain(true));
        } else {
            emitTools(buffer.toString(), output);
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
        var matcher = INVOKE.matcher(source);
        while (matcher.find()) {
            var name = matcher.group(1).trim();
            name = resolveName(name);
            if (name == null) continue;
            var arguments = mapper.createObjectNode();
            var parameter = PARAMETER.matcher(matcher.group(2));
            while (parameter.find()) {
                var raw = parameter.group(2).trim();
                try { arguments.set(parameter.group(1), mapper.readTree(raw)); }
                catch (Exception ignored) { arguments.put(parameter.group(1), raw); }
            }
            calls.add(new ParsedCall(name, arguments));
        }
        var xml = XML_CALL.matcher(source);
        while (xml.find()) {
            var function = XML_FUNCTION.matcher(xml.group(1));
            if (!function.find()) continue;
            var name = resolveName(function.group(1));
            if (name == null) continue;
            var arguments = mapper.createObjectNode();
            var parameter = XML_PARAMETER.matcher(function.group(2));
            while (parameter.find()) putAuto(arguments, parameter.group(1), parameter.group(2));
            calls.add(new ParsedCall(name, arguments));
        }
        var tagged = TAGGED_FUNCTION.matcher(source);
        while (tagged.find()) {
            addJsonCalls(tagged.group(1), calls);
        }
        jsonCandidates(source).forEach(candidate -> addJsonCalls(candidate, calls));
        var plain = PLAIN_CALL.matcher(source);
        while (plain.find()) {
            var name = resolveName(plain.group(1));
            if (name == null) continue;
            var arguments = mapper.createObjectNode();
            var raw = plain.group(2).trim();
            try {
                var parsed = mapper.readTree(raw);
                if (parsed.isObject()) arguments = (tools.jackson.databind.node.ObjectNode) parsed;
            } catch (RuntimeException ignored) {
                for (var pair : raw.split(",")) {
                    var parts = pair.split("=", 2);
                    if (parts.length == 2) putAuto(arguments, parts[0], parts[1]);
                }
            }
            calls.add(new ParsedCall(name, arguments));
        }
        for (var call : calls) {
            var serialized = mapper.writeValueAsString(call.arguments());
            if (!emittedCalls.add(call.name() + ":" + serialized)) continue;
            var id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            output.add(new CanonicalEvent.ToolCallStarted(1, requestId, next(), id, call.name()));
            output.add(new CanonicalEvent.ToolArgumentsDelta(1, requestId, next(), id, serialized));
            output.add(new CanonicalEvent.ToolCallCompleted(1, requestId, next(), id, serialized));
        }
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

    private void addJsonCalls(String source, List<ParsedCall> calls) {
        try {
            var value = mapper.readTree(source.trim());
            var values = value.isArray() ? value
                : value.path("tool_calls").isArray() ? value.path("tool_calls")
                : mapper.createArrayNode().add(value);
            for (var item : values) {
                var function = item.path("function").isObject()
                    ? item.path("function") : item;
                var name = resolveName(function.path("name").asText(""));
                if (name == null) continue;
                var arguments = function.path("arguments");
                if (arguments.isTextual()) arguments = mapper.readTree(arguments.asText());
                calls.add(new ParsedCall(name, arguments.isObject()
                    ? (tools.jackson.databind.node.ObjectNode) arguments
                    : mapper.createObjectNode()));
            }
        } catch (RuntimeException ignored) {
            // Other supported tool syntaxes may still match this response.
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
        String name,
        String raw
    ) {
        var value = raw == null ? "" : raw.trim()
            .replaceFirst("^<!\\[CDATA\\[", "").replaceFirst("]]>$", "").trim();
        try { target.set(name.trim(), mapper.readTree(value)); }
        catch (RuntimeException ignored) { target.put(name.trim(), value); }
    }

    private boolean looksLikeToolSyntax(String source) {
        return List.of("<|MiMoML|tool_calls>", "<tool_call>", "<function_call>",
            "<function_calls>", "TOOL_CALL:").stream().anyMatch(source::contains);
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

    private record ParsedCall(String name, tools.jackson.databind.node.ObjectNode arguments) {}
}
