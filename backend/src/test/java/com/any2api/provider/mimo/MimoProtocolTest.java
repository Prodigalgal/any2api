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
        var decoder = new MimoEventDecoder("r1", List.of());
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
}
