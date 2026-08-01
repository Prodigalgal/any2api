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
                    + "reasoningDeltas={} failures={}",
                requestId, receivedBytes, frames, sseFrames, nonSseFrames, eventTypes, phases,
                reasoningDeltas, failures);
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
        var delta = firstText(payload, "delta_content", "content", "delta");
        if (delta.isEmpty() && payload.path("message").isObject()) {
            delta = firstText(payload.path("message"), "content", "delta_content");
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

    private String firstText(JsonNode source, String... fields) {
        for (var field : fields) {
            if (source.path(field).isTextual()) {
                var value = source.path(field).asText("");
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private void count(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private void usage(JsonNode usage, List<CanonicalEvent> output) {
        if (!usage.isObject()) return;
        var input = usage.path("prompt_tokens").asLong();
        var generated = usage.path("completion_tokens").asLong();
        var cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
        output.add(new CanonicalEvent.Usage(
            1, requestId, next(), input, generated, cached));
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
