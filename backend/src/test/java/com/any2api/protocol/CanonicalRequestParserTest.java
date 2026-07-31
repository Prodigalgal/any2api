package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.routing.ResolvedRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CanonicalRequestParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalRequestParser parser = new CanonicalRequestParser(mapper);
    private final ResolvedRoute route = new ResolvedRoute("qwen", "qwen3.7-plus");

    @Test
    void normalizesResponsesStringInputIntoUserMessage() {
        var raw = mapper.createObjectNode()
            .put("model", "qwen/qwen3.7-plus")
            .put("input", "Only reply QWEN_RESPONSES_OK");

        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().getFirst().path("role").asText()).isEqualTo("user");
        assertThat(request.messages().getFirst().path("content").asText())
            .isEqualTo("Only reply QWEN_RESPONSES_OK");
    }

    @Test
    void normalizesResponsesFunctionOutputIntoToolMessage() {
        var output = mapper.createObjectNode()
            .put("type", "function_call_output")
            .put("call_id", "call-1")
            .set("output", mapper.createObjectNode().put("temperature", 21));
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        raw.putArray("input").add(output);

        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw);

        assertThat(request.messages().getFirst().path("role").asText()).isEqualTo("tool");
        assertThat(request.messages().getFirst().path("tool_call_id").asText())
            .isEqualTo("call-1");
        assertThat(request.messages().getFirst().path("content").asText())
            .isEqualTo("{\"temperature\":21}");
    }

    @Test
    void rejectsUnknownResponsesInputItemsInsteadOfDroppingThem() {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        raw.putArray("input").addObject().put("type", "unknown_item");

        assertThatThrownBy(() ->
            parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported Responses input item type");
    }
}
