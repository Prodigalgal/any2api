package com.any2api.protocol;

import com.any2api.account.AccountUnavailableException;
import com.any2api.provider.ModelRuntimeGuard;
import com.any2api.provider.ModelAvailabilityGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiResponseWriter {

    private final ObjectMapper objectMapper;

    public OpenAiResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(
        CanonicalRequest request,
        Flux<CanonicalEvent> events,
        ServerWebExchange exchange
    ) {
        var guarded = events.onErrorResume(error -> Flux.just(new CanonicalEvent.Failed(
            1,
            request.requestId(),
            1,
            error instanceof AccountUnavailableException
                ? "account_unavailable"
                : error instanceof ModelRuntimeGuard.ModelRuntimeRejectedException
                    || error instanceof ModelAvailabilityGuard.ModelUnavailableException
                    ? "model_unavailable" : "gateway_execution_error",
            error instanceof AccountUnavailableException
                ? error.getMessage()
                : error instanceof ModelRuntimeGuard.ModelRuntimeRejectedException rejected
                    ? rejected.reason()
                    : error instanceof ModelAvailabilityGuard.ModelUnavailableException
                        ? error.getMessage() : "gateway execution failed",
            Map.of(
                "exception", error.getClass().getSimpleName(),
                "retryable", !(error instanceof ModelAvailabilityGuard.ModelUnavailableException)))));
        return request.stream()
            ? writeStream(request, guarded, exchange)
            : writeCollected(request, guarded, exchange);
    }

    private Mono<Void> writeStream(
        CanonicalRequest request,
        Flux<CanonicalEvent> events,
        ServerWebExchange exchange
    ) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        var renderer = request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? (EventRenderer) new ChatStreamRenderer(request, objectMapper)
            : new ResponsesStreamRenderer(request, objectMapper);
        var rendered = events
            .concatMapIterable(renderer::render)
            .publish(shared -> Flux.concat(
                Flux.just(": request_id=" + request.requestId() + "\n\n"),
                Flux.merge(
                    shared,
                    Flux.interval(Duration.ofSeconds(10))
                        .map(ignored -> ": heartbeat\n\n")
                        .takeUntilOther(shared.ignoreElements()))));
        Flux<DataBuffer> body = rendered
            .map(frame -> exchange.getResponse().bufferFactory().wrap(
                frame.getBytes(StandardCharsets.UTF_8)));
        return exchange.getResponse().writeWith(body);
    }

    private Mono<Void> writeCollected(
        CanonicalRequest request,
        Flux<CanonicalEvent> events,
        ServerWebExchange exchange
    ) {
        return events.collectList().flatMap(collected -> {
            var accumulator = new EventAccumulator(request, objectMapper);
            collected.forEach(accumulator::accept);
            var payload = request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS
                ? accumulator.chatResponse()
                : accumulator.responsesResponse();
            exchange.getResponse().setStatusCode(accumulator.status());
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            var bytes = objectMapper.writeValueAsBytes(payload);
            return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(bytes)));
        });
    }

    private interface EventRenderer {
        List<String> render(CanonicalEvent event);
    }

    private static final class ChatStreamRenderer implements EventRenderer {
        private final CanonicalRequest request;
        private final ObjectMapper mapper;
        private final Map<String, Integer> toolIndexes = new LinkedHashMap<>();
        private final boolean includeUsage;
        private CanonicalEvent.Usage pendingUsage;
        private String responseId;

        private ChatStreamRenderer(CanonicalRequest request, ObjectMapper mapper) {
            this.request = request;
            this.mapper = mapper;
            this.includeUsage = request.rawRequest().path("stream_options")
                .path("include_usage").asBoolean(false);
            this.responseId = "chatcmpl_" + request.requestId().replace("-", "");
        }

        @Override
        public List<String> render(CanonicalEvent event) {
            if (event instanceof CanonicalEvent.ResponseStarted started) {
                responseId = started.responseId();
                return List.of(data(chunk(mapper.createObjectNode().put("role", "assistant"), null)));
            }
            if (event instanceof CanonicalEvent.OutputTextDelta text) {
                return List.of(data(chunk(mapper.createObjectNode().put("content", text.delta()), null)));
            }
            if (event instanceof CanonicalEvent.ReasoningDelta reasoning) {
                return List.of(data(chunk(
                    mapper.createObjectNode().put("reasoning_content", reasoning.delta()), null)));
            }
            if (event instanceof CanonicalEvent.ToolCallStarted tool) {
                var index = toolIndexes.computeIfAbsent(tool.toolCallId(), ignored -> toolIndexes.size());
                var function = mapper.createObjectNode().put("name", tool.name()).put("arguments", "");
                var call = mapper.createObjectNode()
                    .put("index", index)
                    .put("id", tool.toolCallId())
                    .put("type", "function")
                    .set("function", function);
                var delta = mapper.createObjectNode();
                delta.putArray("tool_calls").add(call);
                return List.of(data(chunk(delta, null)));
            }
            if (event instanceof CanonicalEvent.ToolArgumentsDelta arguments) {
                var index = toolIndexes.computeIfAbsent(
                    arguments.toolCallId(), ignored -> toolIndexes.size());
                var function = mapper.createObjectNode().put("arguments", arguments.delta());
                var call = mapper.createObjectNode().put("index", index).set("function", function);
                var delta = mapper.createObjectNode();
                delta.putArray("tool_calls").add(call);
                return List.of(data(chunk(delta, null)));
            }
            if (event instanceof CanonicalEvent.Usage usage) {
                if (includeUsage) pendingUsage = usage;
                return List.of();
            }
            if (event instanceof CanonicalEvent.Completed completed) {
                var frames = new ArrayList<String>();
                frames.add(data(chunk(mapper.createObjectNode(), completed.finishReason())));
                if (includeUsage) {
                    var payload = chunk(mapper.createObjectNode(), null);
                    payload.set("choices", mapper.createArrayNode());
                    payload.set("usage", usage(mapper, pendingUsage));
                    frames.add(data(payload));
                }
                frames.add("data: [DONE]\n\n");
                return frames;
            }
            if (event instanceof CanonicalEvent.Failed failed) {
                var error = gatewayError(mapper, request, failed);
                var payload = mapper.createObjectNode().set("error", error);
                return List.of(data(payload), "data: [DONE]\n\n");
            }
            return List.of();
        }

        private ObjectNode chunk(ObjectNode delta, String finishReason) {
            var choice = mapper.createObjectNode().put("index", 0).set("delta", delta);
            if (finishReason == null) {
                choice.putNull("finish_reason");
            } else {
                choice.put("finish_reason", finishReason);
            }
            var payload = mapper.createObjectNode()
                .put("id", responseId)
                .put("object", "chat.completion.chunk")
                .put("created", Instant.now().getEpochSecond())
                .put("model", request.model());
            if (includeUsage) payload.putNull("usage");
            payload.putArray("choices").add(choice);
            return payload;
        }

        private String data(ObjectNode payload) {
            return "data: " + mapper.writeValueAsString(payload) + "\n\n";
        }
    }

    private static final class ResponsesStreamRenderer implements EventRenderer {
        private final CanonicalRequest request;
        private final ObjectMapper mapper;
        private final Map<String, Integer> toolIndexes = new LinkedHashMap<>();
        private long sequence;
        private int nextOutputIndex;
        private Integer textOutputIndex;
        private Integer reasoningOutputIndex;
        private String responseId;
        private final EventAccumulator accumulator;

        private ResponsesStreamRenderer(CanonicalRequest request, ObjectMapper mapper) {
            this.request = request;
            this.mapper = mapper;
            this.responseId = "resp_" + request.requestId().replace("-", "");
            this.accumulator = new EventAccumulator(request, mapper);
        }

        @Override
        public List<String> render(CanonicalEvent event) {
            var frames = new ArrayList<String>();
            accumulator.accept(event);
            if (event instanceof CanonicalEvent.ResponseStarted started) {
                responseId = started.responseId();
                var response = baseResponse("in_progress");
                frames.add(event("response.created", object("response", response)));
                frames.add(event("response.in_progress", object("response", response)));
            } else if (event instanceof CanonicalEvent.OutputTextDelta text) {
                if (textOutputIndex == null) {
                    textOutputIndex = nextOutputIndex++;
                    var item = mapper.createObjectNode()
                        .put("id", "msg_" + responseId)
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("status", "in_progress")
                        .set("content", mapper.createArrayNode());
                    frames.add(event("response.output_item.added", indexedItem(textOutputIndex, item)));
                    var part = mapper.createObjectNode()
                        .put("type", "output_text")
                        .put("text", "")
                        .set("annotations", mapper.createArrayNode())
                        .set("logprobs", mapper.createArrayNode());
                    frames.add(event("response.content_part.added", textPart(part)));
                }
                frames.add(event("response.output_text.delta", textDelta(text.delta())));
            } else if (event instanceof CanonicalEvent.ReasoningDelta reasoning) {
                if (reasoningOutputIndex == null) {
                    reasoningOutputIndex = nextOutputIndex++;
                    var item = mapper.createObjectNode()
                        .put("id", "rs_" + responseId)
                        .put("type", "reasoning")
                        .put("status", "in_progress")
                        .set("summary", mapper.createArrayNode());
                    frames.add(event("response.output_item.added", indexedItem(reasoningOutputIndex, item)));
                }
                var payload = mapper.createObjectNode()
                    .put("item_id", "rs_" + responseId)
                    .put("output_index", reasoningOutputIndex)
                    .put("summary_index", 0)
                    .put("delta", reasoning.delta());
                frames.add(event("response.reasoning_summary_text.delta", payload));
            } else if (event instanceof CanonicalEvent.ToolCallStarted tool) {
                var index = toolIndexes.computeIfAbsent(tool.toolCallId(), ignored -> nextOutputIndex++);
                var item = mapper.createObjectNode()
                    .put("id", "fc_" + tool.toolCallId())
                    .put("type", "function_call")
                    .put("status", "in_progress")
                    .put("call_id", tool.toolCallId())
                    .put("name", tool.name())
                    .put("arguments", "");
                frames.add(event("response.output_item.added", indexedItem(index, item)));
            } else if (event instanceof CanonicalEvent.ToolArgumentsDelta arguments) {
                var index = toolIndexes.computeIfAbsent(arguments.toolCallId(), ignored -> nextOutputIndex++);
                var payload = mapper.createObjectNode()
                    .put("item_id", "fc_" + arguments.toolCallId())
                    .put("output_index", index)
                    .put("delta", arguments.delta());
                frames.add(event("response.function_call_arguments.delta", payload));
            } else if (event instanceof CanonicalEvent.ToolCallCompleted completed) {
                var index = toolIndexes.computeIfAbsent(completed.toolCallId(), ignored -> nextOutputIndex++);
                var tool = accumulator.tools.get(completed.toolCallId());
                var arguments = tool == null ? completed.arguments() : tool.arguments.toString();
                frames.add(event("response.function_call_arguments.done", mapper.createObjectNode()
                    .put("item_id", "fc_" + completed.toolCallId())
                    .put("output_index", index)
                    .put("arguments", arguments)));
                var item = mapper.createObjectNode()
                    .put("id", "fc_" + completed.toolCallId())
                    .put("type", "function_call")
                    .put("status", "completed")
                    .put("call_id", completed.toolCallId())
                    .put("name", tool == null ? "" : tool.name)
                    .put("arguments", arguments);
                frames.add(event("response.output_item.done", indexedItem(index, item)));
            } else if (event instanceof CanonicalEvent.Completed) {
                if (reasoningOutputIndex != null) {
                    frames.add(event("response.reasoning_summary_text.done", mapper.createObjectNode()
                        .put("item_id", "rs_" + responseId)
                        .put("output_index", reasoningOutputIndex)
                        .put("summary_index", 0)
                        .put("text", accumulator.reasoning.toString())));
                    var summary = mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("type", "summary_text").put("text", accumulator.reasoning.toString()));
                    var item = mapper.createObjectNode()
                        .put("id", "rs_" + responseId)
                        .put("type", "reasoning")
                        .put("status", "completed")
                        .set("summary", summary);
                    frames.add(event("response.output_item.done",
                        indexedItem(reasoningOutputIndex, item)));
                }
                if (textOutputIndex != null) {
                    var text = accumulator.text.toString();
                    frames.add(event("response.output_text.done", mapper.createObjectNode()
                        .put("item_id", "msg_" + responseId)
                        .put("output_index", textOutputIndex)
                        .put("content_index", 0)
                        .put("text", text)
                        .set("logprobs", mapper.createArrayNode())));
                    var part = mapper.createObjectNode().put("type", "output_text")
                        .put("text", text)
                        .set("annotations", mapper.createArrayNode())
                        .set("logprobs", mapper.createArrayNode());
                    frames.add(event("response.content_part.done", textPart(part)));
                    var content = mapper.createArrayNode().add(part);
                    var item = mapper.createObjectNode()
                        .put("id", "msg_" + responseId)
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("status", "completed")
                        .set("content", content);
                    frames.add(event("response.output_item.done", indexedItem(textOutputIndex, item)));
                }
                frames.add(event("response.completed",
                    object("response", accumulator.responsesResponse())));
            } else if (event instanceof CanonicalEvent.Failed failed) {
                var response = baseResponse("failed");
                response.set("error", gatewayError(mapper, request, failed));
                frames.add(event("response.failed", object("response", response)));
            }
            return frames;
        }

        private ObjectNode baseResponse(String status) {
            var response = mapper.createObjectNode()
                .put("id", responseId)
                .put("object", "response")
                .put("created_at", Instant.now().getEpochSecond())
                .put("status", status)
                .put("model", request.model())
                .set("output", mapper.createArrayNode());
            applyResponsesConfiguration(response, request, mapper);
            return response;
        }

        private ObjectNode indexedItem(int index, ObjectNode item) {
            return mapper.createObjectNode().put("output_index", index).set("item", item);
        }

        private ObjectNode textPart(ObjectNode part) {
            return mapper.createObjectNode()
                .put("item_id", "msg_" + responseId)
                .put("output_index", textOutputIndex)
                .put("content_index", 0)
                .set("part", part);
        }

        private ObjectNode textDelta(String delta) {
            return mapper.createObjectNode()
                .put("item_id", "msg_" + responseId)
                .put("output_index", textOutputIndex)
                .put("content_index", 0)
                .put("delta", delta)
                .set("logprobs", mapper.createArrayNode());
        }

        private String event(String type, ObjectNode payload) {
            payload.put("type", type).put("sequence_number", sequence++);
            return "event: " + type + "\ndata: " + mapper.writeValueAsString(payload) + "\n\n";
        }

        private ObjectNode object(String key, ObjectNode value) {
            return mapper.createObjectNode().set(key, value);
        }
    }

    private static final class EventAccumulator {
        private final CanonicalRequest request;
        private final ObjectMapper mapper;
        private final Map<String, ToolState> tools = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private String responseId;
        private String finishReason = "stop";
        private CanonicalEvent.Usage usage;
        private CanonicalEvent.Failed failure;

        private EventAccumulator(CanonicalRequest request, ObjectMapper mapper) {
            this.request = request;
            this.mapper = mapper;
            this.responseId = "resp_" + request.requestId().replace("-", "");
        }

        private void accept(CanonicalEvent event) {
            if (event instanceof CanonicalEvent.ResponseStarted started) {
                responseId = started.responseId();
            } else if (event instanceof CanonicalEvent.OutputTextDelta delta) {
                text.append(delta.delta());
            } else if (event instanceof CanonicalEvent.ReasoningDelta delta) {
                reasoning.append(delta.delta());
            } else if (event instanceof CanonicalEvent.ToolCallStarted started) {
                tools.put(started.toolCallId(), new ToolState(started.toolCallId(), started.name()));
            } else if (event instanceof CanonicalEvent.ToolArgumentsDelta delta) {
                tools.computeIfAbsent(delta.toolCallId(), id -> new ToolState(id, ""))
                    .arguments.append(delta.delta());
            } else if (event instanceof CanonicalEvent.ToolCallCompleted completed) {
                var tool = tools.computeIfAbsent(
                    completed.toolCallId(), id -> new ToolState(id, ""));
                if (tool.arguments.isEmpty()) {
                    tool.arguments.append(completed.arguments());
                }
            } else if (event instanceof CanonicalEvent.Usage totals) {
                usage = totals;
            } else if (event instanceof CanonicalEvent.Completed completed) {
                finishReason = completed.finishReason();
            } else if (event instanceof CanonicalEvent.Failed failed) {
                failure = failed;
            }
        }

        private boolean failed() {
            return failure != null;
        }

        private HttpStatus status() {
            if (!failed()) return HttpStatus.OK;
            return Set.of("rate_limited", "quota_exhausted").contains(failure.errorType())
                ? HttpStatus.TOO_MANY_REQUESTS
                : Set.of("account_unavailable", "model_unavailable")
                    .contains(failure.errorType())
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        }

        private ObjectNode chatResponse() {
            if (failure != null) {
                return error();
            }
            var message = mapper.createObjectNode().put("role", "assistant");
            if (text.isEmpty() && !tools.isEmpty()) {
                message.putNull("content");
            } else {
                message.put("content", text.toString());
            }
            if (!reasoning.isEmpty()) {
                message.put("reasoning_content", reasoning.toString());
            }
            if (!tools.isEmpty()) {
                var calls = message.putArray("tool_calls");
                tools.values().forEach(tool -> calls.add(tool(tool)));
            }
            var choice = mapper.createObjectNode()
                .put("index", 0)
                .put("finish_reason", tools.isEmpty() ? finishReason : "tool_calls")
                .set("message", message);
            var payload = mapper.createObjectNode()
                .put("id", responseId.replace("resp_", "chatcmpl_"))
                .put("object", "chat.completion")
                .put("created", Instant.now().getEpochSecond())
                .put("model", request.model());
            payload.putArray("choices").add(choice);
            payload.set("usage", usage(mapper, usage));
            return payload;
        }

        private ObjectNode responsesResponse() {
            if (failure != null) {
                return error();
            }
            var output = mapper.createArrayNode();
            if (!reasoning.isEmpty()) {
                var summary = mapper.createArrayNode().add(
                    mapper.createObjectNode().put("type", "summary_text").put("text", reasoning.toString()));
                output.add(mapper.createObjectNode()
                    .put("id", "rs_" + responseId)
                    .put("type", "reasoning")
                    .put("status", "completed")
                    .set("summary", summary));
            }
            if (!text.isEmpty() || tools.isEmpty()) {
                var content = mapper.createArrayNode().add(mapper.createObjectNode()
                    .put("type", "output_text")
                    .put("text", text.toString())
                    .set("annotations", mapper.createArrayNode())
                    .set("logprobs", mapper.createArrayNode()));
                output.add(mapper.createObjectNode()
                    .put("id", "msg_" + responseId)
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("status", "completed")
                    .set("content", content));
            }
            tools.values().forEach(tool -> output.add(mapper.createObjectNode()
                .put("id", "fc_" + tool.id)
                .put("type", "function_call")
                .put("status", "completed")
                .put("call_id", tool.id)
                .put("name", tool.name)
                .put("arguments", tool.arguments.toString())));
            var response = mapper.createObjectNode()
                .put("id", responseId)
                .put("object", "response")
                .put("created_at", Instant.now().getEpochSecond())
                .put("status", "completed")
                .put("model", request.model())
                .set("output", output)
                .set("usage", responsesUsage(mapper, usage));
            applyResponsesConfiguration(response, request, mapper);
            return response;
        }

        private ObjectNode error() {
            return mapper.createObjectNode().set(
                "error", gatewayError(mapper, request, failure));
        }

        private ObjectNode tool(ToolState tool) {
            return mapper.createObjectNode()
                .put("id", tool.id)
                .put("type", "function")
                .set("function", mapper.createObjectNode()
                    .put("name", tool.name)
                    .put("arguments", tool.arguments.toString()));
        }
    }

    private static ObjectNode gatewayError(
        ObjectMapper mapper,
        CanonicalRequest request,
        CanonicalEvent.Failed failure
    ) {
        var detail = failure.detail() == null ? Map.<String, Object>of() : failure.detail();
        var error = mapper.createObjectNode()
            .put("type", failure.errorType())
            .put("code", failure.errorType())
            .put("message", failure.message())
            .put("retryable", retryable(failure, detail))
            .put("provider", request.providerId())
            .put("model", request.model())
            .put("request_id", request.requestId());
        var parameter = detail.get("param");
        if (parameter == null) error.putNull("param");
        else error.put("param", String.valueOf(parameter));
        return error;
    }

    private static boolean retryable(
        CanonicalEvent.Failed failure,
        Map<String, Object> detail
    ) {
        if (detail.get("retryable") instanceof Boolean value) return value;
        return Set.of(
            "account_unavailable", "rate_limited", "quota_exhausted",
            "empty_model_response", "upstream_unavailable", "gateway_execution_error")
            .contains(failure.errorType());
    }

    private static final class ToolState {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolState(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static ObjectNode usage(ObjectMapper mapper, CanonicalEvent.Usage usage) {
        var input = usage == null ? 0 : usage.inputTokens();
        var output = usage == null ? 0 : usage.outputTokens();
        var cached = usage == null ? 0 : usage.cacheReadTokens();
        var result = mapper.createObjectNode()
            .put("prompt_tokens", input)
            .put("completion_tokens", output)
            .put("total_tokens", input + output)
            .put("usage_source", usage == null ? "ESTIMATED" : usage.source().name())
            .set("prompt_tokens_details",
                mapper.createObjectNode().put("cached_tokens", cached));
        result.set("raw_usage", rawUsage(mapper, usage));
        result.set("normalized_usage", mapper.createObjectNode()
            .put("input_tokens", input).put("output_tokens", output)
            .put("cache_read_tokens", cached));
        return result;
    }

    private static ObjectNode responsesUsage(ObjectMapper mapper, CanonicalEvent.Usage usage) {
        var input = usage == null ? 0 : usage.inputTokens();
        var output = usage == null ? 0 : usage.outputTokens();
        var cached = usage == null ? 0 : usage.cacheReadTokens();
        var result = mapper.createObjectNode()
            .put("input_tokens", input)
            .put("output_tokens", output)
            .put("total_tokens", input + output)
            .put("usage_source", usage == null ? "ESTIMATED" : usage.source().name())
            .set("input_tokens_details", mapper.createObjectNode().put("cached_tokens", cached));
        result.set("raw_usage", rawUsage(mapper, usage));
        result.set("normalized_usage", mapper.createObjectNode()
            .put("input_tokens", input).put("output_tokens", output)
            .put("cache_read_tokens", cached));
        return result;
    }

    private static ObjectNode rawUsage(ObjectMapper mapper, CanonicalEvent.Usage usage) {
        return mapper.createObjectNode()
            .put("input_tokens", usage == null ? 0 : usage.rawInputTokens())
            .put("output_tokens", usage == null ? 0 : usage.rawOutputTokens())
            .put("cache_read_tokens", usage == null ? 0 : usage.rawCacheReadTokens());
    }

    private static void applyResponsesConfiguration(
        ObjectNode response,
        CanonicalRequest request,
        ObjectMapper mapper
    ) {
        var raw = request.rawRequest();
        response.putNull("error");
        response.putNull("incomplete_details");
        response.set("metadata", raw.path("metadata").isObject()
            ? raw.path("metadata").deepCopy() : mapper.createObjectNode());
        response.put("parallel_tool_calls", raw.path("parallel_tool_calls").asBoolean(true));
        response.put("store", raw.path("store").asBoolean(false));
        response.set("tools", raw.path("tools").isArray()
            ? raw.path("tools").deepCopy() : mapper.createArrayNode());
        response.set("tool_choice", raw.has("tool_choice")
            ? raw.path("tool_choice").deepCopy()
            : mapper.getNodeFactory().textNode("auto"));
        copyOrNull(response, raw, "instructions");
        copyOrNull(response, raw, "max_output_tokens");
        copyOrNull(response, raw, "previous_response_id");
        copyOrNull(response, raw, "reasoning");
        copyOrNull(response, raw, "service_tier");
        copyOrNull(response, raw, "temperature");
        copyOrNull(response, raw, "text");
        copyOrNull(response, raw, "top_p");
        copyOrNull(response, raw, "truncation");
    }

    private static void copyOrNull(
        ObjectNode target,
        tools.jackson.databind.JsonNode source,
        String field
    ) {
        if (source.has(field)) target.set(field, source.path(field).deepCopy());
        else target.putNull(field);
    }

}
