package com.any2api.provider.qwen;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class QwenEventDecoder {
    private final String requestId;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sequence;
    private boolean started;
    private boolean completed;
    private boolean failed;
    private boolean emittedOutput;
    private boolean emittedUsage;
    private String responseId;

    QwenEventDecoder(String requestId) { this.requestId = requestId; }

    List<CanonicalEvent> decode(String data) {
        var output = new ArrayList<CanonicalEvent>();
        if (data == null || data.isBlank()) return output;
        if (completed || failed) return output;
        if ("[DONE]".equals(data.trim())) {
            complete(output, "stop");
            return output;
        }
        try {
            var object = mapper.readTree(data);
            var created = object.path("response.created");
            if (created.isObject()) {
                var id = created.path("response_id").asText("");
                if (!id.isBlank()) responseId = id;
            }
            var choice = firstChoice(object);
            if (hasError(object)) {
                fail(output, errorType(object));
            } else if (choice != null) decodeChoice(choice, output);
            else {
                var content = firstText(object, "response", "text", "content");
                if (!content.isBlank()) text(output, content);
                var nested = object.path("data");
                if (nested.isObject()) {
                    content = firstText(nested, "text", "content");
                    if (!content.isBlank()) text(output, content);
                }
            }
            usage(object.path("usage"), output);
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("Qwen upstream emitted invalid stream JSON", error);
        }
    }

    List<CanonicalEvent> finish() {
        if (completed || failed) return List.of();
        var output = new ArrayList<CanonicalEvent>();
        complete(output, "stop");
        return output;
    }

    private JsonNode firstChoice(JsonNode object) {
        if (object.path("choices").isArray() && !object.path("choices").isEmpty()) {
            return object.path("choices").get(0);
        }
        var data = object.path("data");
        if (data.path("choices").isArray() && !data.path("choices").isEmpty()) {
            return data.path("choices").get(0);
        }
        var output = object.path("output");
        if (output.path("choices").isArray() && !output.path("choices").isEmpty()) {
            return output.path("choices").get(0);
        }
        return null;
    }

    private void decodeChoice(JsonNode choice, List<CanonicalEvent> output) {
        var delta = choice.path("delta").isObject() ? choice.path("delta") : choice.path("message");
        var phase = delta.path("phase").asText("");
        var content = delta.path("content").asText(choice.path("text").asText(""));
        if (!content.isEmpty()) {
            if (phase.startsWith("think") || List.of("reason", "reasoning").contains(phase)) {
                start(output);
                output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), content));
                emittedOutput = true;
            } else {
                text(output, content);
            }
        }
        var reasoning = delta.path("reasoning_content").asText("");
        if (!reasoning.isEmpty()) {
            start(output);
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), reasoning));
            emittedOutput = true;
        }
        if (delta.path("tool_calls").isArray()) decodeTools(delta.path("tool_calls"), output);
        var finish = choice.path("finish_reason").asText("");
        if (!finish.isBlank()) complete(output, finish);
    }

    private void decodeTools(JsonNode calls, List<CanonicalEvent> output) {
        for (var call : calls) {
            start(output);
            var id = call.path("id").asText("call_" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24));
            var function = call.path("function");
            var name = function.path("name").asText(call.path("name").asText(""));
            var arguments = function.path("arguments").asText(call.path("arguments").asText("{}"));
            output.add(new CanonicalEvent.ToolCallStarted(1, requestId, next(), id, name));
            output.add(new CanonicalEvent.ToolArgumentsDelta(1, requestId, next(), id, arguments));
            output.add(new CanonicalEvent.ToolCallCompleted(1, requestId, next(), id, arguments));
            emittedOutput = true;
        }
    }

    private void usage(JsonNode usage, List<CanonicalEvent> output) {
        if (!usage.isObject() || !emittedOutput || emittedUsage) return;
        var input = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong());
        var generated = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong());
        if (input > 0 || generated > 0) {
            output.add(new CanonicalEvent.Usage(1, requestId, next(), input, generated, 0));
            emittedUsage = true;
        }
    }

    private String firstText(JsonNode object, String... fields) {
        for (var field : fields) {
            var value = object.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return "";
    }

    private void text(List<CanonicalEvent> output, String value) {
        start(output);
        output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), value));
        emittedOutput = true;
    }

    private void complete(List<CanonicalEvent> output, String reason) {
        if (!completed && !failed && !emittedOutput) {
            output.add(new CanonicalEvent.Failed(1, requestId, next(),
                "empty_model_response", "Qwen returned no model output", Map.of()));
        } else if (!completed && !failed) {
            output.add(new CanonicalEvent.Completed(1, requestId, next(), reason));
        }
        completed = true;
    }

    private void fail(List<CanonicalEvent> output, String type) {
        if (completed || failed) return;
        output.add(new CanonicalEvent.Failed(
            1, requestId, next(), type, "Qwen completion error class=" + type, Map.of()));
        failed = true;
        completed = true;
    }

    private boolean hasError(JsonNode object) {
        return object.hasNonNull("error")
            || object.path("data").hasNonNull("error")
            || object.path("output").hasNonNull("error")
            || object.path("response").hasNonNull("error");
    }

    private String errorType(JsonNode object) {
        var text = errorText(object).toLowerCase(java.util.Locale.ROOT);
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
        if (text.contains("rate") || text.contains("too many") || text.contains("throttl")
            || text.contains("429")) {
            return "rate_limited";
        }
        if (text.contains("quota") || text.contains("credit") || text.contains("balance")
            || text.contains("usage limit") || text.contains("limit reached")
            || text.contains("exhaust")) {
            return "quota_exhausted";
        }
        if (text.contains("captcha") || text.contains("verify")) return "captcha_rejected";
        if (text.contains("token") || text.contains("auth") || text.contains("login")) {
            return "credential_rejected";
        }
        return "provider_upstream_error";
    }

    private String errorText(JsonNode object) {
        var output = new StringBuilder();
        for (var source : errorSources(object)) {
            for (var field : List.of("code", "type", "name", "status", "message", "detail")) {
                var value = source.path(field);
                if (value.isValueNode() && !value.isNull()) {
                    output.append(' ').append(value.asText());
                }
            }
            if (source.isValueNode() && !source.isNull()) output.append(' ').append(source.asText());
        }
        return output.toString();
    }

    private List<JsonNode> errorSources(JsonNode object) {
        return List.of(
            object.path("error"),
            object.path("error").path("data"),
            object.path("data").path("error"),
            object.path("output").path("error"),
            object.path("response").path("error"));
    }

    private void start(List<CanonicalEvent> output) {
        if (!started) {
            output.add(new CanonicalEvent.ResponseStarted(1, requestId, next(),
                responseId == null || responseId.isBlank()
                    ? "resp_" + requestId.replace("-", "") : responseId));
            started = true;
        }
    }

    private long next() { return sequence++; }
}
