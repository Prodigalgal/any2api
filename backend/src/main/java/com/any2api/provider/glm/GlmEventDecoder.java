package com.any2api.provider.glm;

import com.any2api.protocol.CanonicalEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GlmEventDecoder {
    private final String requestId;
    private final ObjectMapper mapper;
    private final StringBuilder buffer = new StringBuilder();
    private long sequence;
    private boolean started;
    private boolean completed;

    GlmEventDecoder(String requestId, ObjectMapper mapper) {
        this.requestId = requestId;
        this.mapper = mapper;
    }

    List<CanonicalEvent> decode(byte[] chunk) {
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
        return output;
    }

    private void decodeFrame(String frame, List<CanonicalEvent> output) {
        var data = new StringBuilder();
        for (var line : frame.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).trim());
        }
        if (data.isEmpty()) return;
        if ("[DONE]".contentEquals(data)) {
            complete(output, "stop");
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(data.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("GLM upstream emitted invalid stream JSON", error);
        }
        start(output);
        if (!"chat:completion".equals(root.path("type").asText(""))) {
            if (root.path("type").asText("").contains("error")) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "provider_upstream_error", root.path("data").toString(), Map.of()));
            }
            return;
        }
        var payload = root.path("data");
        var phase = payload.path("phase").asText("");
        var delta = payload.path("delta_content").asText("");
        if (!delta.isEmpty()) {
            if (List.of("thinking", "reasoning").contains(phase)) {
                output.add(new CanonicalEvent.ReasoningDelta(
                    1, requestId, next(), delta));
            } else if ("answer".equals(phase)) {
                output.add(new CanonicalEvent.OutputTextDelta(
                    1, requestId, next(), delta));
            }
        }
        usage(payload.path("usage"), output);
        if (payload.path("done").asBoolean(false) || "done".equals(phase)) {
            complete(output, "stop");
        }
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
