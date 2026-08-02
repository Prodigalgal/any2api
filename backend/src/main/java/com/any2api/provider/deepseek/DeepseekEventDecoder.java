package com.any2api.provider.deepseek;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class DeepseekEventDecoder {
    private final String requestId;
    private final ObjectMapper mapper;
    private long sequence;
    private long usage;
    private boolean started;
    private boolean completed;
    private boolean emittedAnswer;
    private FragmentType active = FragmentType.RESPONSE;

    DeepseekEventDecoder(String requestId, ObjectMapper mapper) {
        this.requestId = requestId;
        this.mapper = mapper;
    }

    List<CanonicalEvent> decode(String data) {
        var output = start();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return output;
        final JsonNode event;
        try {
            event = mapper.readTree(data);
        } catch (RuntimeException error) {
            throw new DeepseekUpstreamException(502, "DeepSeek emitted invalid SSE JSON");
        }
        var bizCode = event.path("data").path("biz_code").asInt(0);
        if (bizCode != 0) {
            throw new DeepseekUpstreamException(400,
                "DeepSeek stream rejected the request (biz_code=" + bizCode + ")");
        }
        var response = event.path("v").path("response");
        if (response.isObject()) {
            emitFragments(response.path("fragments"), output);
            usage = Math.max(usage, response.path("accumulated_token_usage").asLong(0));
            return output;
        }
        var path = event.path("p").asText("");
        var value = event.path("v");
        if (path.endsWith("accumulated_token_usage") && value.isNumber()) {
            usage = Math.max(usage, value.asLong());
        } else if (path.equals("response/fragments") && value.isArray()) {
            emitFragments(value, output);
        } else if (path.contains("fragments") && path.endsWith("content")
            && value.isTextual()) {
            emit(value.asText(), output);
        } else if (path.isBlank() && value.isTextual()) {
            emit(value.asText(), output);
        }
        return output;
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        if (!emittedAnswer) {
            output.add(new CanonicalEvent.Failed(1, requestId, next(),
                "empty_model_response", "DeepSeek returned no answer text", java.util.Map.of()));
        } else {
            if (usage > 0) output.add(new CanonicalEvent.Usage(
                1, requestId, next(), 0, usage, 0));
            output.add(new CanonicalEvent.Completed(1, requestId, next(), "stop"));
        }
        completed = true;
        return output;
    }

    private void emitFragments(JsonNode fragments, List<CanonicalEvent> output) {
        if (!fragments.isArray()) return;
        for (var fragment : fragments) {
            active = FragmentType.parse(fragment.path("type").asText("RESPONSE"));
            emit(fragment.path("content").asText(""), output);
        }
    }

    private void emit(String value, List<CanonicalEvent> output) {
        if (value == null || value.isEmpty()) return;
        if (active == FragmentType.THINK) {
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), value));
        } else {
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), value));
            if (!value.isBlank()) emittedAnswer = true;
        }
    }

    private ArrayList<CanonicalEvent> start() {
        var output = new ArrayList<CanonicalEvent>();
        if (!started) {
            output.add(new CanonicalEvent.ResponseStarted(
                1, requestId, next(), "resp_" + requestId.replace("-", "")));
            started = true;
        }
        return output;
    }

    private long next() { return sequence++; }

    private enum FragmentType {
        THINK,
        RESPONSE;

        static FragmentType parse(String value) {
            return "THINK".equalsIgnoreCase(value) ? THINK : RESPONSE;
        }
    }
}
