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
    void prependsResponsesInstructionsAsSystemContext() {
        var raw = mapper.createObjectNode()
            .put("model", "qwen/qwen3.7-plus")
            .put("instructions", "Reply in JSON")
            .put("input", "hello");

        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw);

        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().getFirst().path("role").asText()).isEqualTo("system");
        assertThat(request.messages().getFirst().path("content").asText())
            .isEqualTo("Reply in JSON");
    }

    @Test
    void rejectsNonTextResponsesInstructions() {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        raw.putObject("instructions").put("unexpected", true);

        assertThatThrownBy(() -> parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("instructions must be a string");
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

    @Test
    void rejectsProviderOptionsForAnotherExplicitRoute() {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        raw.putArray("messages").addObject().put("role", "user").put("content", "hello");
        raw.putObject("provider_options").putObject("mimo").put("thinking", true);

        assertThatThrownBy(() -> parser.parse(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, route, raw))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("does not match the resolved provider");
    }

    @Test
    void rejectsConflictingTokenLimitAliases() {
        var raw = mapper.createObjectNode()
            .put("model", "qwen/qwen3.7-plus")
            .put("max_tokens", 100)
            .put("max_completion_tokens", 200);
        raw.putArray("messages").addObject().put("role", "user").put("content", "hello");

        assertThatThrownBy(() -> parser.parse(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, route, raw))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("cannot be supplied together");
    }
}
