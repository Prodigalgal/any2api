package com.any2api.provider.minmax;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class MinmaxEventDecoder {
    private static final Pattern TOKEN_PLAN_LIMIT = Pattern.compile(
        "^\\s*(42212)\\s*:\\s*(Token Plan usage limit reached.*?)"
            + "(?:\\s+\\((\\d+)\\))?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private final String requestId;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean emittedText;

    MinmaxEventDecoder(String requestId) { this.requestId = requestId; }

    List<CanonicalEvent> decode(String data) {
        if (completed) return List.of();
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
        if (emittedText) {
            output.add(new CanonicalEvent.Completed(1, requestId, next(), "stop"));
        } else {
            output.add(new CanonicalEvent.Failed(1, requestId, next(),
                "empty_model_response", "MinMax returned no model output", Map.of()));
        }
        completed = true;
        return output;
    }

    private void decodeChunk(JsonNode chunk, List<CanonicalEvent> output) {
        var reasoning = firstText(chunk, "thinking_content", "reasoning_content");
        if (!reasoning.isBlank()) {
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), reasoning));
        }
        var text = firstText(chunk, "content", "text", "msg_content");
        if (!text.isBlank() && !decodeSemanticFailure(text, output)) {
            emittedText = true;
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), text));
        }
    }

    private void decodeMessage(JsonNode message, List<CanonicalEvent> output) {
        if (!"assistant".equalsIgnoreCase(message.path("role").asText(""))) return;
        var text = text(message.path("msg_content"));
        if (!text.isBlank() && !emittedText && !decodeSemanticFailure(text, output)) {
            emittedText = true;
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), text));
        }
    }

    private boolean decodeSemanticFailure(String text, List<CanonicalEvent> output) {
        var match = TOKEN_PLAN_LIMIT.matcher(text);
        if (!match.matches()) return false;
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("upstream_code", match.group(1));
        if (match.group(3) != null) detail.put("reason_code", match.group(3));
        output.add(new CanonicalEvent.Failed(1, requestId, next(),
            "quota_exhausted", text.trim(), Map.copyOf(detail)));
        completed = true;
        return true;
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
