package com.any2api.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiSseEventDecoder {

    private final ObjectMapper mapper;
    private final String requestId;
    private final Map<String, ToolState> tools = new LinkedHashMap<>();
    private final Map<Integer, String> chatToolIds = new LinkedHashMap<>();
    private long sequence;
    private String responseId;
    private boolean started;
    private boolean terminal;
    private boolean usageEmitted;

    public OpenAiSseEventDecoder(ObjectMapper mapper, String requestId) {
        this.mapper = mapper;
        this.requestId = requestId;
        this.responseId = "resp_" + requestId.replace("-", "");
    }

    public List<CanonicalEvent> decode(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return List.of();
        }
        try {
            var event = mapper.readTree(data);
            return event.has("choices") ? decodeChatChunk(event) : decodeResponsesEvent(event);
        } catch (Exception error) {
            throw new IllegalArgumentException("upstream emitted invalid SSE JSON", error);
        }
    }

    public List<CanonicalEvent> finish() {
        if (terminal) return List.of();
        terminal = true;
        return List.of(new CanonicalEvent.Failed(
            1,
            requestId,
            next(),
            "incomplete_upstream_stream",
            "upstream stream ended without a terminal event",
            Map.of()));
    }

    private List<CanonicalEvent> decodeResponsesEvent(JsonNode event) {
        var output = new ArrayList<CanonicalEvent>();
        var type = event.path("type").asText("");
        switch (type) {
            case "response.created", "response.in_progress" -> {
                var id = event.path("response").path("id").asText(event.path("id").asText(""));
                start(output, id);
            }
            case "response.output_text.delta" -> addText(output, event.path("delta").asText(""));
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                addReasoning(output, event.path("delta").asText(""));
            case "response.output_item.added" -> addTool(output, event.path("item"));
            case "response.function_call_arguments.delta" -> {
                var itemId = event.path("item_id").asText(event.path("call_id").asText("call_node"));
                var delta = event.path("delta").asText("");
                if (!delta.isEmpty()) {
                    var state = tool(itemId);
                    state.arguments.append(delta);
                    output.add(new CanonicalEvent.ToolArgumentsDelta(
                        1, requestId, next(), state.callId, delta));
                }
            }
            case "response.output_item.done" -> completeTool(output, event.path("item"));
            case "response.completed" -> {
                start(output, event.path("response").path("id").asText(""));
                var response = event.path("response");
                addUsage(output, response.path("usage"));
                output.add(new CanonicalEvent.Completed(
                    1,
                    requestId,
                    next(),
                    tools.isEmpty() ? "stop" : "tool_calls"));
                terminal = true;
            }
            case "response.incomplete" -> {
                var response = event.path("response");
                var reason = response.path("incomplete_details").path("reason")
                    .asText("incomplete");
                output.add(new CanonicalEvent.Failed(
                    1, requestId, next(), "incomplete_response",
                    "upstream response was incomplete", Map.of("reason", reason)));
                terminal = true;
            }
            case "response.failed", "error" -> {
                var error = event.path("response").path("error");
                if (error.isMissingNode()) {
                    error = event.path("error");
                }
                output.add(new CanonicalEvent.Failed(
                    1,
                    requestId,
                    next(),
                    error.path("code").asText("provider_error"),
                    error.path("message").asText("upstream request failed"),
                    Map.of()));
                terminal = true;
            }
            default -> {
            }
        }
        return output;
    }

    private List<CanonicalEvent> decodeChatChunk(JsonNode chunk) {
        var output = new ArrayList<CanonicalEvent>();
        var id = chunk.path("id").asText("");
        start(output, id);
        addUsage(output, chunk.path("usage"));
        for (var choice : chunk.path("choices")) {
            var delta = choice.path("delta");
            addText(output, delta.path("content").asText(""));
            addReasoning(output, delta.path("reasoning_content").asText(""));
            for (var rawCall : delta.path("tool_calls")) {
                var index = rawCall.path("index").asInt(0);
                var explicitId = rawCall.path("id").asText("");
                if (!explicitId.isBlank()) chatToolIds.put(index, explicitId);
                var idValue = explicitId.isBlank()
                    ? chatToolIds.computeIfAbsent(index, key -> "call_node_" + key)
                    : explicitId;
                var state = tool(idValue);
                var name = rawCall.path("function").path("name").asText("");
                if (!name.isBlank() && state.name.isBlank()) {
                    state.name = name;
                    output.add(new CanonicalEvent.ToolCallStarted(
                        1, requestId, next(), state.callId, name));
                }
                var arguments = rawCall.path("function").path("arguments").asText("");
                if (!arguments.isEmpty()) {
                    state.arguments.append(arguments);
                    output.add(new CanonicalEvent.ToolArgumentsDelta(
                        1, requestId, next(), state.callId, arguments));
                }
            }
            if (!choice.path("finish_reason").isNull()
                && !choice.path("finish_reason").asText("").isBlank()) {
                tools.values().forEach(state -> output.add(new CanonicalEvent.ToolCallCompleted(
                    1,
                    requestId,
                    next(),
                    state.callId,
                    state.arguments.toString())));
                output.add(new CanonicalEvent.Completed(
                    1,
                    requestId,
                    next(),
                    choice.path("finish_reason").asText("stop")));
                terminal = true;
            }
        }
        return output;
    }

    private void addTool(List<CanonicalEvent> output, JsonNode item) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        start(output, "");
        var itemId = item.path("id").asText(item.path("call_id").asText("call_node"));
        var callId = item.path("call_id").asText(itemId);
        var state = tool(itemId);
        state.callId = callId;
        state.name = item.path("name").asText("");
        output.add(new CanonicalEvent.ToolCallStarted(
            1, requestId, next(), state.callId, state.name));
        var arguments = item.path("arguments").asText("");
        if (!arguments.isEmpty()) {
            state.arguments.append(arguments);
            output.add(new CanonicalEvent.ToolArgumentsDelta(
                1, requestId, next(), state.callId, arguments));
        }
    }

    private void completeTool(List<CanonicalEvent> output, JsonNode item) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        var itemId = item.path("id").asText(item.path("call_id").asText("call_node"));
        var state = tool(itemId);
        var callId = item.path("call_id").asText("");
        if (!callId.isBlank()) state.callId = callId;
        if (state.name.isBlank()) {
            state.name = item.path("name").asText("");
        }
        var arguments = item.path("arguments").asText("");
        if (state.arguments.isEmpty() && !arguments.isEmpty()) {
            state.arguments.append(arguments);
        }
        output.add(new CanonicalEvent.ToolCallCompleted(
            1, requestId, next(), state.callId, state.arguments.toString()));
    }

    private void addText(List<CanonicalEvent> output, String delta) {
        if (!delta.isEmpty()) {
            start(output, "");
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), delta));
        }
    }

    private void addReasoning(List<CanonicalEvent> output, String delta) {
        if (!delta.isEmpty()) {
            start(output, "");
            output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), delta));
        }
    }

    private void addUsage(List<CanonicalEvent> output, JsonNode usage) {
        if (!usage.isObject() || usageEmitted) {
            return;
        }
        var input = usage.path("input_tokens").asLong(usage.path("prompt_tokens").asLong(0));
        var outputTokens = usage.path("output_tokens").asLong(
            usage.path("completion_tokens").asLong(0));
        var cached = usage.path("input_tokens_details").path("cached_tokens").asLong(
            usage.path("cached_tokens").asLong(0));
        output.add(new CanonicalEvent.Usage(
            1, requestId, next(), input, outputTokens, cached));
        usageEmitted = true;
    }

    private ToolState tool(String id) {
        return tools.computeIfAbsent(id, ToolState::new);
    }

    private void start(List<CanonicalEvent> output, String id) {
        if (!id.isBlank()) responseId = id;
        if (started) return;
        output.add(new CanonicalEvent.ResponseStarted(1, requestId, next(), responseId));
        started = true;
    }

    private long next() {
        return sequence++;
    }

    private static final class ToolState {
        private final String id;
        private String callId;
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private ToolState(String id) {
            this.id = id;
            this.callId = id;
        }
    }
}
