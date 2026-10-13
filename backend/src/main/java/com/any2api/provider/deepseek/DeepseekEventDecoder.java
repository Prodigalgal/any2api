package com.any2api.provider.deepseek;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class DeepseekEventDecoder {
    private static final int CREDENTIAL_REJECTED_CODE = 40003;
    private static final int ACCOUNT_MUTED_BIZ_CODE = 5;
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
        if (completed) return List.of();
        var output = start();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return output;
        final JsonNode event;
        try {
            event = mapper.readTree(data);
        } catch (RuntimeException error) {
            throw new DeepseekUpstreamException(502, "DeepSeek emitted invalid SSE JSON");
        }
        var failure = upstreamFailure(event);
        if (failure != null) {
            output.add(failure);
            return output;
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
            output.add(new CanonicalEvent.Completed(1, requestId, next(), "stop"));
        }
        completed = true;
        return output;
    }

    private CanonicalEvent.Failed upstreamFailure(JsonNode event) {
        var code = event.path("code").asInt(0);
        if (code != 0) {
            return reject(code == CREDENTIAL_REJECTED_CODE ? "credential_rejected" : "provider_upstream_error",
                "DeepSeek rejected the request (code=" + code + ")",
                Map.of("upstream_code", code, "retryable", false));
        }
        var business = event.path("data");
        var bizCode = business.path("biz_code").asInt(0);
        if (bizCode == 0) return null;
        var detail = new HashMap<String, Object>();
        detail.put("upstream_biz_code", bizCode);
        detail.put("retryable", false);
        var muted = bizCode == ACCOUNT_MUTED_BIZ_CODE
            && business.path("biz_data").path("is_muted").asInt(0) == 1;
        if (muted) {
            var mutedUntil = business.path("biz_data").path("mute_until").asLong(0);
            if (mutedUntil > 0) detail.put("upstream_muted_until_epoch_seconds", mutedUntil);
        }
        return reject(muted ? "account_blocked" : "provider_upstream_error",
            muted ? "DeepSeek account is temporarily muted by upstream"
                : "DeepSeek rejected the request (biz_code=" + bizCode + ")",
            Map.copyOf(detail));
    }

    private CanonicalEvent.Failed reject(String errorType, String message, Map<String, Object> detail) {
        completed = true;
        return new CanonicalEvent.Failed(1, requestId, next(), errorType, message, detail);
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
