package com.any2api.provider.longcat;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class LongcatEventDecoder {
    private final String requestId;
    private final boolean reasoningEnabled;
    private final LongcatToolProtocol.Plan toolPlan;
    private final LongcatToolProtocol toolProtocol;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder cumulative = new StringBuilder();
    private final List<String> contentParts = new ArrayList<>();
    private long sequence;
    private boolean started;
    private boolean completed;

    LongcatEventDecoder(
        String requestId,
        boolean reasoningEnabled,
        LongcatToolProtocol.Plan toolPlan,
        LongcatToolProtocol toolProtocol
    ) {
        this.requestId = requestId;
        this.reasoningEnabled = reasoningEnabled;
        this.toolPlan = toolPlan;
        this.toolProtocol = toolProtocol;
    }

    List<CanonicalEvent> decode(String data) {
        var output = start();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return output;
        try {
            var chunk = mapper.readTree(data);
            if (chunk.has("code") && chunk.path("code").asInt() != 0) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "provider_upstream_error", chunk.path("message").asText("LongCat request failed"),
                    Map.of("code", chunk.path("code").asInt())));
                completed = true;
                return output;
            }
            var event = chunk.path("event");
            var type = event.path("type").asText("");
            switch (type) {
                case "reason", "think" -> reasoning(output, event.path("content").asText(""));
                case "content" -> text(output, event.path("content").asText(""), false);
                case "common_search", "general_search", "local_life_search" ->
                    text(output, searchText(event.path("content")), false);
                case "finish" -> {
                    var finalText = event.path("finalContentX").asText(
                        event.path("finalContent").asText(""));
                    usage(output, event.path("usage"), chunk.path("tokenInfo"));
                    finishOutput(output, event.path("finishType").asText("").equals("sensitive")
                        ? "content_filter" : "stop", finalText);
                }
                case "event_error" -> {
                    output.add(new CanonicalEvent.Failed(1, requestId, next(),
                        "provider_upstream_error", event.path("message").asText(
                            event.path("content").asText("LongCat request failed")), Map.of()));
                    completed = true;
                }
                default -> {
                    if (type.isBlank() && chunk.path("content").isTextual()) {
                        text(output, chunk.path("content").asText(""), true);
                        usage(output, chunk.path("usage"), chunk.path("tokenInfo"));
                    }
                }
            }
            if (chunk.path("lastOne").asBoolean(false) && !completed) {
                finishOutput(output, "stop", "");
            }
            return output;
        } catch (Exception error) {
            throw new IllegalArgumentException("LongCat upstream emitted invalid SSE JSON", error);
        }
    }

    List<CanonicalEvent> finish() {
        if (completed) return List.of();
        var output = start();
        finishOutput(output, "stop", "");
        return output;
    }

    private void text(List<CanonicalEvent> output, String value, boolean isCumulative) {
        if (value.isBlank()) return;
        var delta = value;
        if (isCumulative) {
            delta = value.startsWith(cumulative.toString())
                ? value.substring(cumulative.length()) : value;
            cumulative.setLength(0);
            cumulative.append(value);
        } else cumulative.append(value);
        if (!delta.isEmpty()) contentParts.add(delta);
        if (!delta.isEmpty() && !toolPlan.enabled() && !reasoningEnabled) {
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), delta));
        }
    }

    private void reasoning(List<CanonicalEvent> output, String value) {
        if (!value.isEmpty()) output.add(new CanonicalEvent.ReasoningDelta(
            1, requestId, next(), value));
    }

    private String searchText(tools.jackson.databind.JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) return "";
        var parts = new ArrayList<String>();
        for (var item : value) {
            if (item.isTextual()) parts.add(item.asText());
            else if (item.isObject()) parts.add((item.path("title").asText("") + " "
                + item.path("snippet").asText(item.path("content").asText(""))).trim());
        }
        return String.join("\n", parts);
    }

    private void usage(List<CanonicalEvent> output, tools.jackson.databind.JsonNode usage,
                       tools.jackson.databind.JsonNode tokenInfo) {
        var input = usage.path("inputTokens").asLong(tokenInfo.path("promptTokens").asLong());
        var generated = usage.path("outputTokens").asLong(tokenInfo.path("completionTokens").asLong());
        if (input > 0 || generated > 0) {
            output.add(new CanonicalEvent.Usage(1, requestId, next(), input, generated, 0));
        }
    }

    private void complete(List<CanonicalEvent> output, String reason) {
        if (!completed) output.add(new CanonicalEvent.Completed(1, requestId, next(), reason));
        completed = true;
    }

    private void finishOutput(List<CanonicalEvent> output, String reason, String finalText) {
        var answer = resolvedAnswer(output, finalText);
        if (!toolPlan.enabled()) {
            if (answer.isBlank()) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "empty_model_response", "LongCat returned no model output", Map.of()));
                completed = true;
                return;
            }
            if (reasoningEnabled && !answer.isBlank()) {
                output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), answer));
            } else if (!reasoningEnabled && cumulative.isEmpty() && !answer.isBlank()) {
                output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), answer));
            }
            complete(output, reason);
            return;
        }
        var calls = toolProtocol.parse(answer, toolPlan);
        if (calls.isEmpty()) {
            if (toolPlan.required()) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "tool_call_generation_failed",
                    "LongCat did not produce the required function tool call", Map.of()));
                completed = true;
                return;
            }
            if (answer.isBlank()) {
                output.add(new CanonicalEvent.Failed(1, requestId, next(),
                    "empty_model_response", "LongCat returned no model output", Map.of()));
                completed = true;
                return;
            }
            if (!answer.isBlank()) {
                output.add(new CanonicalEvent.OutputTextDelta(
                    1, requestId, next(), answer));
            }
            complete(output, reason);
            return;
        }
        if (!toolPlan.parallel() && calls.size() > 1) {
            output.add(new CanonicalEvent.Failed(1, requestId, next(),
                "tool_call_generation_failed",
                "LongCat produced parallel calls while parallel_tool_calls=false", Map.of()));
            completed = true;
            return;
        }
        for (var call : calls) {
            output.add(new CanonicalEvent.ToolCallStarted(
                1, requestId, next(), call.id(), call.name()));
            output.add(new CanonicalEvent.ToolArgumentsDelta(
                1, requestId, next(), call.id(), call.arguments()));
            output.add(new CanonicalEvent.ToolCallCompleted(
                1, requestId, next(), call.id(), call.arguments()));
        }
        complete(output, "tool_calls");
    }

    private String resolvedAnswer(List<CanonicalEvent> output, String finalText) {
        if (!reasoningEnabled) {
            return cumulative.isEmpty() ? finalText : cumulative.toString();
        }
        if (!finalText.isBlank()) {
            var reasoning = String.join("", contentParts);
            if (!reasoning.isBlank()) {
                output.add(new CanonicalEvent.ReasoningDelta(
                    1, requestId, next(), reasoning));
            }
            return finalText;
        }
        if (contentParts.size() <= 1) return cumulative.toString();
        var reasoning = String.join("", contentParts.subList(0, contentParts.size() - 1));
        if (!reasoning.isBlank()) {
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), reasoning));
        }
        return contentParts.getLast();
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
