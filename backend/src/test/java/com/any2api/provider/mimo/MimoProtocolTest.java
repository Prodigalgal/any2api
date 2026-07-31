package com.any2api.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MimoProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsResponsesInputAndDecodesReasoningTextAndUsage() {
        var raw = mapper.createObjectNode().put("model", "mimo/mimo-v2.5-pro");
        raw.putArray("input").add(mapper.createObjectNode().put("role", "user")
            .put("content", "hello"));
        var request = new CanonicalRequest("r1", CanonicalRequest.Protocol.RESPONSES,
            "mimo", "mimo-v2.5-pro", true,
            List.of(raw.path("input").get(0)), Map.of(), Map.of("effort", "high"),
            List.of(), Map.of(), raw);

        var prepared = new MimoRequestMapper(mapper).prepare(request);
        var decoder = new MimoEventDecoder("r1", List.of(), false, true);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"type\":\"text\",\"content\":\"<think>why</think>answer\"}"));
        events.addAll(decoder.decode("{\"promptTokens\":3,\"completionTokens\":2,\"totalTokens\":5}"));
        events.addAll(decoder.finish());

        assertThat(prepared.body().path("query").asText()).contains("hello");
        assertThat(prepared.body().path("modelConfig").path("enableThinking").asBoolean()).isTrue();
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Usage.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void preservesInlineImageForTheProviderUploadPipeline() {
        var raw = mapper.createObjectNode().put("model", "mimo/mimo-v2.5");
        var content = raw.putArray("messages").addObject()
            .put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "inspect");
        content.addObject().put("type", "image_url")
            .putObject("image_url").put("url", "data:image/png;base64,iVBORw0KGgo=");
        var request = new CanonicalRequest("r2", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "mimo", "mimo-v2.5", false,
            List.of(raw.path("messages").get(0)), Map.of(), Map.of(), List.of(), Map.of(), raw);

        var prepared = new MimoRequestMapper(mapper).prepare(request);

        assertThat(prepared.body().path("query").asText()).contains("inspect");
        assertThat(prepared.media()).singleElement().satisfies(media -> {
            assertThat(media.kind()).isEqualTo("image");
            assertThat(media.dataUrl()).startsWith("data:image/png;base64,");
        });
    }

    @Test
    void decodesRequiredMimoMlToolCallsAndRejectsMissingCalls() {
        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var function = raw.putArray("tools").addObject().put("type", "function")
            .putObject("function");
        function.put("name", "get_weather").putObject("parameters").put("type", "object");
        var request = new CanonicalRequest("tools", CanonicalRequest.Protocol.RESPONSES,
            "mimo", "mimo-v2.5-pro", true, List.of(), Map.of(), Map.of(),
            List.of(raw.path("tools").get(0)), Map.of(), raw);
        var prepared = new MimoRequestMapper(mapper).prepare(request);
        var decoder = new MimoEventDecoder("tools", prepared.tools(), prepared.toolRequired(),
            prepared.parallelToolCalls());

        decoder.decode("{\"type\":\"text\",\"content\":\"<|MiMoML|tool_calls>"
            + "<|MiMoML|invoke name='get_weather'><|MiMoML|parameter name='city'>"
            + "\\\"Xiamen\\\"</|MiMoML|parameter></|MiMoML|invoke>"
            + "</|MiMoML|tool_calls>\"}");
        var events = decoder.finish();

        assertThat(events).anyMatch(CanonicalEvent.ToolCallStarted.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));

        var missing = new MimoEventDecoder("missing", prepared.tools(), true, true);
        missing.decode("{\"type\":\"text\",\"content\":\"plain answer\"}");
        assertThat(missing.finish()).anyMatch(event ->
            event instanceof CanonicalEvent.Failed failed
                && failed.errorType().equals("tool_call_generation_failed"));
    }

    @Test
    void rejectsAnEmptyMimoStream() {
        var events = new MimoEventDecoder("empty", List.of(), false, true).finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }
}
