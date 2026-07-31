package com.any2api.provider.grok_web;

import com.any2api.protocol.CanonicalEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GrokWebEventDecoder {
    private final ObjectMapper mapper;
    private final String requestId;
    private final String responseId;
    private final JsonObjectStreamFramer frames;
    private final StringBuilder emittedText = new StringBuilder();
    private final StringBuilder receivedText = new StringBuilder();
    private final GrokWebToolProtocol.StreamSieve toolSieve;
    private long sequence;
    private boolean started;
    private String conversationId = "";
    private String upstreamResponseId = "";
    private boolean hasToolCalls;

    GrokWebEventDecoder(ObjectMapper mapper, String requestId) {
        this(mapper, requestId, "", null);
    }

    GrokWebEventDecoder(ObjectMapper mapper, String requestId, String conversationId) {
        this(mapper, requestId, conversationId, null);
    }

    GrokWebEventDecoder(
        ObjectMapper mapper,
        String requestId,
        String conversationId,
        GrokWebToolProtocol.StreamSieve toolSieve
    ) {
        this.mapper = mapper;
        this.frames = new JsonObjectStreamFramer(mapper);
        this.requestId = requestId;
        this.responseId = "resp_" + requestId.replace("-", "");
        this.conversationId = conversationId == null ? "" : conversationId;
        this.toolSieve = toolSieve;
    }

    List<CanonicalEvent> decode(byte[] chunk) {
        var output = new ArrayList<CanonicalEvent>();
        for (var frame : frames.decode(chunk)) output.addAll(parse(frame));
        return output;
    }

    List<CanonicalEvent> finish() {
        frames.finish();
        var output = new ArrayList<CanonicalEvent>();
        start(output);
        if (toolSieve != null) emitToolResult(output, toolSieve.flush());
        output.add(new CanonicalEvent.Usage(1, requestId, next(), 0, 0, 0));
        output.add(new CanonicalEvent.Completed(
            1, requestId, next(), hasToolCalls ? "tool_calls" : "stop"));
        return output;
    }

    private List<CanonicalEvent> parse(JsonNode root) {
        if (root.path("event").isObject()) return parseGateway(root.path("event"));
        var output = new ArrayList<CanonicalEvent>();
        if (root.path("error").isObject()) throw upstreamError(root.path("error"));
        var result = root.path("result");
        if (result.path("conversation").isObject()) {
            conversationId = result.path("conversation").path("conversationId").asText(conversationId);
            start(output);
        }
        var response = result.path("response");
        if (!response.isObject()) response = result;
        if (!response.isObject()) return output;
        if (response.path("error").isObject()) throw upstreamError(response.path("error"));
        start(output);
        var token = response.path("token").asText("");
        var tag = response.path("messageTag").asText("");
        if (!token.isEmpty() && !"tool_usage_card".equals(tag)) {
            if (response.path("isThinking").asBoolean(false)) {
                output.add(new CanonicalEvent.ReasoningDelta(1, requestId, next(), token));
            } else {
                receivedText.append(token);
                emitVisibleText(output, token);
            }
        }
        var modelResponse = response.path("modelResponse");
        var finalMessage = modelResponse.path("message").asText("");
        upstreamResponseId = modelResponse.path("responseId")
            .asText(response.path("responseId").asText(upstreamResponseId));
        if (!finalMessage.isBlank() && finalMessage.startsWith(receivedText.toString())) {
            var delta = finalMessage.substring(receivedText.length());
            if (!delta.isEmpty()) {
                receivedText.append(delta);
                emitVisibleText(output, delta);
            }
        }
        appendImage(output, response.path("streamingImageGenerationResponse"));
        for (var image : modelResponse.path("generatedImageUrls")) {
            appendImageUrl(output, image.asText(""));
        }
        return output;
    }

    private List<CanonicalEvent> parseGateway(JsonNode event) {
        var output = new ArrayList<CanonicalEvent>();
        var type = event.path("type").asText("");
        if ("error".equals(type)) throw upstreamError(event.path("error"));
        if ("conversation.attached".equals(type)) {
            conversationId = event.path("conversation").path("id").asText(conversationId);
            return output;
        }
        if ("response.created".equals(type)) {
            upstreamResponseId = event.path("response").path("id")
                .asText(upstreamResponseId);
            start(output);
            return output;
        }
        if ("response.chunk".equals(type)) {
            var text = event.path("chunk").path("text");
            var value = text.path("text").asText("");
            var channel = text.path("channel").asText("");
            if ("CHANNEL_ASSISTANT_RESPONSE".equals(channel)) {
                receivedText.append(value);
                start(output);
                emitVisibleText(output, value);
            } else if (channel.contains("ANALYSIS") || channel.contains("REASONING")) {
                start(output);
                if (!value.isEmpty()) {
                    output.add(new CanonicalEvent.ReasoningDelta(
                        1, requestId, next(), value));
                }
            }
            return output;
        }
        if ("response.output_text.delta".equals(type)) {
            var value = event.path("delta").asText(event.path("text").asText(""));
            receivedText.append(value);
            start(output);
            emitVisibleText(output, value);
            return output;
        }
        if ("response.done".equals(type)) {
            upstreamResponseId = event.path("response").path("id")
                .asText(upstreamResponseId);
            start(output);
        }
        return output;
    }

    private void appendImage(List<CanonicalEvent> output, JsonNode image) {
        if (!image.isObject() || image.path("moderated").asBoolean(false)) return;
        if (image.path("isFinal").asBoolean(false) || image.path("progress").asInt(0) == 100) {
            appendImageUrl(output, image.path("imageUrl").asText(image.path("url").asText("")));
        }
    }

    private void emitVisibleText(List<CanonicalEvent> output, String value) {
        if (value.isEmpty() || hasToolCalls) return;
        if (toolSieve == null) {
            emittedText.append(value);
            output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), value));
            return;
        }
        emitToolResult(output, toolSieve.feed(value));
    }

    private void emitToolResult(
        List<CanonicalEvent> output,
        GrokWebToolProtocol.SieveResult result
    ) {
        if (!result.safeText().isEmpty()) {
            emittedText.append(result.safeText());
            output.add(new CanonicalEvent.OutputTextDelta(
                1, requestId, next(), result.safeText()));
        }
        for (var call : result.calls()) {
            hasToolCalls = true;
            output.add(new CanonicalEvent.ToolCallStarted(
                1, requestId, next(), call.id(), call.name()));
            output.add(new CanonicalEvent.ToolArgumentsDelta(
                1, requestId, next(), call.id(), call.arguments()));
            output.add(new CanonicalEvent.ToolCallCompleted(
                1, requestId, next(), call.id(), call.arguments()));
        }
    }

    private void appendImageUrl(List<CanonicalEvent> output, String url) {
        if (url.isBlank()) return;
        var absolute = url.startsWith("/") ? "https://assets.grok.com" + url : url;
        var markdown = "\n![generated image](" + absolute + ")\n";
        emittedText.append(markdown);
        output.add(new CanonicalEvent.OutputTextDelta(1, requestId, next(), markdown));
    }

    private RuntimeException upstreamError(JsonNode error) {
        return new GrokWebStreamException(error.path("code").asText("provider_error"),
            error.path("message").asText("Grok Web stream failed"));
    }

    private void start(List<CanonicalEvent> output) {
        if (!started) {
            started = true;
            output.add(new CanonicalEvent.ResponseStarted(1, requestId, next(), responseId));
        }
    }

    private long next() { return sequence++; }

    String responseId() { return responseId; }

    Optional<JsonNode> responseState() {
        if (conversationId.isBlank() || upstreamResponseId.isBlank()) return Optional.empty();
        return Optional.of(mapper.createObjectNode()
            .put("conversation_id", conversationId)
            .put("upstream_response_id", upstreamResponseId));
    }

    static final class GrokWebStreamException extends RuntimeException {
        private final String code;
        GrokWebStreamException(String code, String message) { super(message); this.code = code; }
        String code() { return code; }
    }
}
