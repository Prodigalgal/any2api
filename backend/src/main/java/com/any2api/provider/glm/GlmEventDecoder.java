package com.any2api.provider.glm;

import com.any2api.protocol.CanonicalEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GlmEventDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlmEventDecoder.class);

    private final String requestId;
    private final ObjectMapper mapper;
    private final StringBuilder buffer = new StringBuilder();
    private final Map<String, Integer> eventTypes = new LinkedHashMap<>();
    private final Map<String, Integer> phases = new LinkedHashMap<>();
    private final Map<String, Integer> payloadShapes = new LinkedHashMap<>();
    private final Map<String, Integer> errorCodes = new LinkedHashMap<>();
    private long sequence;
    private long receivedBytes;
    private int frames;
    private int sseFrames;
    private int nonSseFrames;
    private int answerDeltas;
    private int reasoningDeltas;
    private int failures;
    private boolean started;
    private boolean completed;
    private boolean usageEmitted;

    GlmEventDecoder(String requestId, ObjectMapper mapper) {
        this.requestId = requestId;
        this.mapper = mapper;
    }

    List<CanonicalEvent> decode(byte[] chunk) {
        receivedBytes += chunk.length;
        buffer.append(new String(chunk, StandardCharsets.UTF_8));
        var output = new ArrayList<CanonicalEvent>();
        while (true) {
            var boundary = boundary(buffer);
            if (boundary < 0) break;
            var frame = buffer.substring(0, boundary);
            var delimiter = buffer.indexOf("\r\n\r\n") == boundary ? 4 : 2;
            buffer.delete(0, boundary + delimiter);
            decodeFrame(frame, output);
        }
        return output;
    }

    List<CanonicalEvent> finish() {
        var output = new ArrayList<CanonicalEvent>();
        if (!buffer.isEmpty()) decodeFrame(buffer.toString(), output);
        buffer.setLength(0);
        if (!completed) complete(output, "stop");
        if (answerDeltas == 0) {
            LOGGER.warn(
                "GLM stream completed without answer deltas requestId={} bytes={} frames={} "
                    + "sseFrames={} nonSseFrames={} eventTypes={} phases={} "
                    + "payloadShapes={} errorCodes={} reasoningDeltas={} failures={}",
                requestId, receivedBytes, frames, sseFrames, nonSseFrames, eventTypes, phases,
                payloadShapes, errorCodes, reasoningDeltas, failures);
        }
        return output;
    }

    private void decodeFrame(String frame, List<CanonicalEvent> output) {
        frames++;
        var data = new StringBuilder();
        for (var line : frame.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).trim());
        }
        if (data.isEmpty()) {
            var payload = frame.trim();
            if (payload.isEmpty()) return;
            nonSseFrames++;
            if ("[DONE]".equals(payload)) {
                complete(output, "stop");
            } else if (payload.startsWith("{")) {
                decodeJson(payload, false, output);
            }
            return;
        }
        sseFrames++;
        if ("[DONE]".contentEquals(data)) {
            complete(output, "stop");
            return;
        }
        decodeJson(data.toString(), true, output);
    }

    private void decodeJson(String data, boolean sse, List<CanonicalEvent> output) {
        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (Exception error) {
            throw new IllegalArgumentException("GLM upstream emitted invalid stream JSON", error);
        }
        start(output);
        var type = root.path("type").asText("").trim();
        count(eventTypes, type.isEmpty() ? "<missing>" : type);
        var payload = payload(root.path("data"));
        count(payloadShapes, payloadShape(payload));
        var phase = payload.path("phase").asText("").trim().toLowerCase();
        if (!phase.isEmpty()) count(phases, phase);
        var completion = "chat:completion".equals(type)
            || (payload.isObject()
                && (payload.has("phase") || payload.has("delta_content")));
        if (!completion) {
            if (type.contains("error") || root.hasNonNull("error") || !sse) {
                failures++;
                output.add(new CanonicalEvent.Failed(
                    1, requestId, next(), "provider_upstream_error",
                    "GLM upstream returned a non-completion stream payload", Map.of()));
            }
            return;
        }
        if (payload.hasNonNull("error")) {
            var errorType = errorType(payload);
            var errorCode = errorCode(payload);
            if (!errorCode.isEmpty()) count(errorCodes, errorCode);
            failures++;
            output.add(new CanonicalEvent.Failed(
                1, requestId, next(), errorType,
                "GLM completion error class=" + errorType, Map.of()));
            return;
        }
        var delta = firstContent(payload, "delta_content", "content", "delta", "output_text");
        if (delta.isEmpty()) delta = content(payload.path("message"));
        var choices = payload.path("choices");
        if (delta.isEmpty() && choices.isArray() && !choices.isEmpty()) {
            var choice = choices.get(0);
            delta = content(choice.path("delta"));
            if (delta.isEmpty()) delta = content(choice.path("message"));
            if (delta.isEmpty()) delta = firstContent(choice, "text", "content");
        }
        if (!delta.isEmpty()) {
            if (List.of("thinking", "reasoning").contains(phase)) {
                reasoningDeltas++;
                output.add(new CanonicalEvent.ReasoningDelta(
                    1, requestId, next(), delta));
            } else if (!List.of("other", "done", "usage").contains(phase)) {
                answerDeltas++;
                output.add(new CanonicalEvent.OutputTextDelta(
                    1, requestId, next(), delta));
            }
        }
        usage(payload.path("usage"), output);
        if (payload.path("done").asBoolean(false) || "done".equals(phase)) {
            complete(output, "stop");
        }
    }

    private JsonNode payload(JsonNode value) {
        if (!value.isTextual()) return value;
        var text = value.asText("").trim();
        if (!text.startsWith("{")) return value;
        try {
            return mapper.readTree(text);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String firstContent(JsonNode source, String... fields) {
        for (var field : fields) {
            var value = content(source.path(field));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String content(JsonNode value) {
        if (value.isTextual()) return value.asText("");
        if (value.isArray()) {
            var output = new StringBuilder();
            for (var item : value) output.append(content(item));
            return output.toString();
        }
        if (!value.isObject()) return "";
        return firstContent(value, "text", "content", "value", "delta_content");
    }

    private String shape(JsonNode value) {
        if (!value.isObject()) return value.getNodeType().name().toLowerCase();
        var fields = new ArrayList<String>();
        value.properties().forEach(entry -> fields.add(
            entry.getKey() + ":" + entry.getValue().getNodeType().name().toLowerCase()));
        fields.sort(String::compareTo);
        return "{" + String.join(",", fields) + "}";
    }

    private String payloadShape(JsonNode payload) {
        var output = "payload=" + shape(payload);
        if (payload.path("error").isObject() || payload.path("error").isArray()) {
            output += ";error=" + shape(payload.path("error"));
        }
        if (payload.path("data").isObject() || payload.path("data").isArray()) {
            output += ";data=" + shape(payload.path("data"));
        }
        return output;
    }

    private String errorType(JsonNode payload) {
        var text = errorText(payload).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("permission") || text.contains("forbidden")) {
            return "permission_denied";
        }
        if (text.contains("account") && (text.contains("unavailable")
            || text.contains("disabled") || text.contains("blocked"))) {
            return "account_unavailable";
        }
        if (text.contains("model") && (text.contains("unavailable")
            || text.contains("not found") || text.contains("invalid"))) {
            return "model_unavailable";
        }
        if (text.contains("quota") || text.contains("credit") || text.contains("balance")) {
            return "quota_exhausted";
        }
        if (text.contains("captcha") || text.contains("verify")) return "captcha_rejected";
        if (text.contains("rate") || text.contains("too many")) return "rate_limited";
        if (text.contains("token") || text.contains("auth") || text.contains("login")) {
            return "credential_rejected";
        }
        return "provider_upstream_error";
    }

    private String errorCode(JsonNode payload) {
        for (var source : errorSources(payload)) {
            for (var field : List.of("code", "type", "name", "status")) {
                var value = scalar(source.path(field));
                if (!value.isEmpty()) {
                    return value.replaceAll("[^A-Za-z0-9_.:-]", "_").substring(
                        0, Math.min(80, value.length()));
                }
            }
        }
        return "<missing>";
    }

    private String errorText(JsonNode payload) {
        var output = new StringBuilder();
        for (var source : errorSources(payload)) {
            for (var field : List.of("code", "type", "name", "status", "message", "detail")) {
                var value = scalar(source.path(field));
                if (!value.isEmpty()) output.append(' ').append(value);
            }
        }
        return output.toString();
    }

    private List<JsonNode> errorSources(JsonNode payload) {
        return List.of(
            payload.path("error"),
            payload.path("error").path("data"),
            payload.path("data"),
            payload.path("data").path("error"));
    }

    private String scalar(JsonNode value) {
        return value.isValueNode() && !value.isNull() ? value.asText("").trim() : "";
    }

    private void count(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private void usage(JsonNode usage, List<CanonicalEvent> output) {
        if (!usage.isObject() || usageEmitted) return;
        var input = usage.path("prompt_tokens").asLong();
        var generated = usage.path("completion_tokens").asLong();
        var cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
        output.add(new CanonicalEvent.Usage(
            1, requestId, next(), input, generated, cached));
        usageEmitted = true;
    }

    private void start(List<CanonicalEvent> output) {
        if (started) return;
        output.add(new CanonicalEvent.ResponseStarted(
            1, requestId, next(), "resp_" + requestId.replace("-", "")));
        started = true;
    }

    private void complete(List<CanonicalEvent> output, String reason) {
        start(output);
        if (!completed) {
            output.add(new CanonicalEvent.Completed(1, requestId, next(), reason));
            completed = true;
        }
    }

    private int boundary(StringBuilder value) {
        var lf = value.indexOf("\n\n");
        var crlf = value.indexOf("\r\n\r\n");
        if (lf < 0) return crlf;
        if (crlf < 0) return lf;
        return Math.min(lf, crlf);
    }

    private long next() { return sequence++; }
}
