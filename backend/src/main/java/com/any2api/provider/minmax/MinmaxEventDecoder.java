package com.any2api.provider.minmax;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class MinmaxEventDecoder {
    private final String requestId;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean emittedText;

    MinmaxEventDecoder(String requestId) { this.requestId = requestId; }

    List<CanonicalEvent> decode(String data) {
        var output = start();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return output;
        try {
            var event = mapper.readTree(data);
            switch (event.path("type").asInt(-1)) {
                case 6 -> decodeChunk(event.path("agent_message_chunk"), output);
                case 2 -> decodeMessage(event.path("agent_message"), output);
                default -> {
                    if (event.path("error").isObject()) {
                        output.add(new CanonicalEvent.Failed(1, requestId, next(),
                            "provider_upstream_error", event.path("error").path("message")
                                .asText("MinMax request failed"), Map.of()));
                        completed = true;
                    }
                }
            }
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("MinMax upstream emitted invalid SSE JSON", error);
        }
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        output.add(new CanonicalEvent.Completed(1, requestId, next(), "stop"));
        completed = true;
        return output;
    }

    private void decodeChunk(JsonNode chunk, List<CanonicalEvent> output) {
        var reasoning = firstText(chunk, "thinking_content", "reasoning_content");
        if (!reasoning.isBlank()) {
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), reasoning));
        }
        var text = firstText(chunk, "content", "text", "msg_content");
        if (!text.isBlank()) {
            emittedText = true;
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), text));
        }
    }

    private void decodeMessage(JsonNode message, List<CanonicalEvent> output) {
        if (!"assistant".equalsIgnoreCase(message.path("role").asText(""))) return;
        var text = text(message.path("msg_content"));
        if (!text.isBlank() && !emittedText) {
            emittedText = true;
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), text));
        }
    }

    private String firstText(JsonNode value, String... fields) {
        for (var field : fields) {
            var result = text(value.path(field));
            if (!result.isBlank()) return result;
        }
        return "";
    }

    private String text(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isArray()) {
            var parts = new ArrayList<String>();
            for (var part : value) {
                var text = firstText(part, "text", "content");
                if (!text.isBlank()) parts.add(text);
            }
            return String.join("", parts);
        }
        return value.isObject() ? firstText(value, "text", "content") : "";
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
