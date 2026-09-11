package com.any2api.provider.arena;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Decodes Arena's one-character-prefixed newline-delimited Web stream. */
final class ArenaEventDecoder {
    private final String requestId;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean failed;
    private boolean emittedOutput;
    private boolean emittedUsage;
    private String responseId;

    ArenaEventDecoder(String requestId) { this.requestId = requestId; }

    List<CanonicalEvent> decode(String data) {
        var output = new ArrayList<CanonicalEvent>();
        if (data == null || data.isBlank() || completed || failed) return output;
        var frame = data.trim();
        if (frame.startsWith("data:")) frame = frame.substring(5).trim();
        if ("[DONE]".equals(frame)) {
            complete(output, "stop");
            return output;
        }
        if (frame.length() < 2) {
            fail(output, "provider_protocol_violation");
            return output;
        }
        var code = frame.charAt(0);
        var payload = frame.substring(1).trim();
        if (payload.startsWith(":")) payload = payload.substring(1).trim();
        JsonNode value;
        try {
            value = mapper.readTree(payload);
        } catch (RuntimeException error) {
            fail(output, "provider_protocol_violation");
            return output;
        }
        switch (code) {
            case '0' -> text(value, output);
            case '2' -> data(value, output);
            case '3' -> fail(output, classifyError(textValue(value)));
            case '8', 'h', 'i', 'j' -> { /* annotations, sources, and reasoning metadata */ }
            case '9', 'a', 'b', 'c', 'k' -> fail(output, "unsupported_model_output");
            case 'd' -> {
                usage(value.path("usage"), output);
                complete(output, firstText(value, "finishReason", "finish_reason", "reason", "stop"));
            }
            case 'e' -> {
                if (!value.path("isContinued").asBoolean(false)) {
                    usage(value.path("usage"), output);
                    complete(output, firstText(value, "finishReason", "finish_reason", "reason", "stop"));
                }
            }
            case 'f' -> {
                var id = firstText(value, "messageId", "message_id", "id", "responseId");
                if (!id.isBlank()) responseId = id;
                start(output);
            }
            case 'g' -> reasoning(value, output);
            default -> fail(output, "provider_protocol_violation");
        }
        return output;
    }

    List<CanonicalEvent> finish() {
        if (completed || failed) return List.of();
        var output = new ArrayList<CanonicalEvent>();
        complete(output, "stop");
        return output;
    }

    private void text(JsonNode value, List<CanonicalEvent> output) {
        var text = textValue(value);
        if (!text.isBlank()) {
            start(output);
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), text));
            emittedOutput = true;
        }
    }

    private void reasoning(JsonNode value, List<CanonicalEvent> output) {
        var text = textValue(value);
        if (!text.isBlank()) {
            start(output);
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), text));
            emittedOutput = true;
        }
    }

    private void data(JsonNode value, List<CanonicalEvent> output) {
        if (!value.isArray()) {
            fail(output, "provider_protocol_violation");
            return;
        }
        for (var item : value) {
            if (item.isTextual()) {
                text(item, output);
                continue;
            }
            if (!item.isObject()) continue;
            var type = item.path("type").asText("").toLowerCase(Locale.ROOT);
            if (SetLike.TEXT_TYPES.contains(type)) {
                text(item.path("text").isTextual() ? item.path("text") : item.path("content"), output);
            } else if (SetLike.MEDIA_TYPES.contains(type)) {
                fail(output, "unsupported_model_output");
                return;
            } else if (!SetLike.SEARCH_TYPES.contains(type)) {
                fail(output, "provider_protocol_violation");
                return;
            }
        }
    }

    private void usage(JsonNode value, List<CanonicalEvent> output) {
        if (!value.isObject() || !emittedOutput || emittedUsage) return;
        var input = firstLong(value, "promptTokens", "prompt_tokens", "inputTokens", "input_tokens");
        var generated = firstLong(
            value, "completionTokens", "completion_tokens", "outputTokens", "output_tokens");
        if (input >= 0 || generated >= 0) {
            output.add(new CanonicalEvent.Usage(1, requestId, next(),
                Math.max(0, input), Math.max(0, generated), 0));
            emittedUsage = true;
        }
    }

    private void complete(List<CanonicalEvent> output, String reason) {
        if (completed || failed) return;
        if (!emittedOutput) {
            fail(output, "empty_model_response");
            return;
        }
        output.add(new CanonicalEvent.Completed(1, requestId, next(),
            reason == null || reason.isBlank() ? "stop" : reason));
        completed = true;
    }

    private void fail(List<CanonicalEvent> output, String type) {
        if (completed || failed) return;
        output.add(new CanonicalEvent.Failed(
            1, requestId, next(), type, "Arena completion error class=" + type, Map.of()));
        failed = true;
        completed = true;
    }

    private void start(List<CanonicalEvent> output) {
        if (started) return;
        output.add(new CanonicalEvent.ResponseStarted(1, requestId, next(),
            responseId == null || responseId.isBlank() ? "resp_" + requestId.replace("-", "") : responseId));
        started = true;
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isObject()) {
            for (var field : List.of("text", "content", "message", "error", "detail")) {
                var child = value.path(field);
                if (child.isTextual()) return child.asText();
            }
        }
        return value.isValueNode() ? value.asText("") : "";
    }

    private String firstText(JsonNode value, String... fields) {
        for (var field : fields) {
            var child = value.path(field);
            if (child.isTextual() && !child.asText().isBlank()) return child.asText();
        }
        return "stop";
    }

    private long firstLong(JsonNode value, String... fields) {
        for (var field : fields) {
            var child = value.path(field);
            if (child.isIntegralNumber()) return child.asLong();
        }
        return -1;
    }

    private String classifyError(String raw) {
        var text = (raw == null ? "" : raw).toLowerCase(Locale.ROOT);
        if (contains(text, "captcha", "recaptcha", "challenge", "verify", "bot")) {
            return "captcha_rejected";
        }
        if (contains(text, "quota", "credit", "balance", "usage limit", "limit reached")) {
            return "quota_exhausted";
        }
        if (contains(text, "rate", "too many", "throttl", "429")) return "rate_limited";
        if (contains(text, "unauthorized", "user not found", "auth", "login", "token")) {
            return "credential_rejected";
        }
        if (contains(text, "forbidden", "permission")) return "permission_denied";
        if (contains(text, "model") && contains(text, "invalid", "missing", "unavailable")) {
            return "model_unavailable";
        }
        if (contains(text, "invalid", "bad request", "validation")) {
            return "invalid_request_error";
        }
        return "provider_upstream_error";
    }

    private boolean contains(String value, String... markers) {
        for (var marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private long next() { return sequence++; }

    private static final class SetLike {
        private static final java.util.Set<String> TEXT_TYPES = java.util.Set.of(
            "text", "output_text", "message");
        private static final java.util.Set<String> MEDIA_TYPES = java.util.Set.of(
            "image", "image_url", "video", "file", "attachment");
        private static final java.util.Set<String> SEARCH_TYPES = java.util.Set.of(
            "search", "source", "sources");

        private SetLike() {}
    }
}
