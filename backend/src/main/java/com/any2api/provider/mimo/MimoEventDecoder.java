package com.any2api.provider.mimo;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.HashSet;
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

    private final String requestId;
    private final List<MimoTool> tools;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder buffer = new StringBuilder();
    private final Set<String> emittedCalls = new HashSet<>();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean reasoning;

    MimoEventDecoder(String requestId, List<MimoTool> tools) {
        this.requestId = requestId;
        this.tools = tools;
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
                    output.addAll(drain(false));
                }
            } else if (event.has("promptTokens")) {
                output.add(new CanonicalEvent.Usage(1, requestId, next(),
                    event.path("promptTokens").asLong(), event.path("completionTokens").asLong(), 0));
            }
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("MiMo upstream emitted invalid SSE JSON", error);
        }
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        output.addAll(drain(true));
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
        else output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), value));
    }

    private void emitTools(String source, List<CanonicalEvent> output) {
        var matcher = INVOKE.matcher(source);
        while (matcher.find()) {
            var name = matcher.group(1).trim();
            if (tools.stream().noneMatch(tool -> tool.name().equals(name))) continue;
            var arguments = mapper.createObjectNode();
            var parameter = PARAMETER.matcher(matcher.group(2));
            while (parameter.find()) {
                var raw = parameter.group(2).trim();
                try { arguments.set(parameter.group(1), mapper.readTree(raw)); }
                catch (Exception ignored) { arguments.put(parameter.group(1), raw); }
            }
            var serialized = mapper.writeValueAsString(arguments);
            if (!emittedCalls.add(name + ":" + serialized)) continue;
            var id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            output.add(new CanonicalEvent.ToolCallStarted(1, requestId, next(), id, name));
            output.add(new CanonicalEvent.ToolArgumentsDelta(1, requestId, next(), id, serialized));
            output.add(new CanonicalEvent.ToolCallCompleted(1, requestId, next(), id, serialized));
        }
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
}
