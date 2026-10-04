package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.routing.ResolvedRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class CanonicalRequestParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalRequestParser parser = new CanonicalRequestParser(mapper);
    private final ResolvedRoute route = new ResolvedRoute("qwen", "qwen3.7-plus");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void normalizesAssistantToolReplayWithNullOrMissingContent(boolean explicitNull) {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        var messages = raw.putArray("messages");
        messages.addObject().put("role", "user").put("content", "Inspect the directory");
        var assistant = messages.addObject().put("role", "assistant").put("phase", "commentary");
        if (explicitNull) assistant.putNull("content");
        var calls = assistant.putArray("tool_calls");
        calls.addObject().put("id", "call-1").put("type", "function")
            .putObject("function").put("name", "inspect_workspace").put("arguments", "{}");
        messages.addObject().put("role", "tool").put("tool_call_id", "call-1")
            .put("content", "demo.txt");
        var original = raw.deepCopy();

        var request = parser.parse(CanonicalRequest.Protocol.CHAT_COMPLETIONS, route, raw);

        var replay = request.messages().get(1);
        assertThat(replay.path("content").isTextual()).isTrue();
        assertThat(replay.path("content").asText()).isEmpty();
        assertThat(replay.path("tool_calls")).isEqualTo(calls);
        assertThat(replay.path("phase").asText()).isEqualTo("commentary");
        assertThat(request.messages().getLast().path("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(raw).isEqualTo(original);
        assertThat(request.rawRequest()).isEqualTo(original);
    }

    @Test
    void preservesTextAndMediaToolReplayAndDoesNotNormalizeOtherNullContent() {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus");
        var messages = raw.putArray("messages");
        messages.addObject().put("role", "user").putNull("content");
        messages.addObject().put("role", "assistant").putNull("content");
        var assistant = messages.addObject().put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "call-1");
        var content = assistant.putArray("content");
        content.addObject().put("type", "text").put("text", "Inspecting");
        content.addObject().put("type", "image_url")
            .putObject("image_url").put("url", "https://example.com/demo.png");

        var request = parser.parse(CanonicalRequest.Protocol.CHAT_COMPLETIONS, route, raw);

        assertThat(request.messages().getFirst().path("content").isNull()).isTrue();
        assertThat(request.messages().get(1).path("content").isNull()).isTrue();
        assertThat(request.messages().getLast().path("content")).isEqualTo(content);
    }

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
    void treatsNullableResponsesControlsAsOmittedWithoutChangingRawRequest() {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus").put("input", "hello");
        for (var field : new String[] {"instructions", "reasoning", "stream_options", "temperature", "top_p", "max_output_tokens"}) {
            raw.putNull(field);
        }
        var original = raw.deepCopy();

        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().getFirst().path("content").asText()).isEqualTo("hello");
        assertThat(request.generation()).isEmpty();
        assertThat(request.reasoning()).isEmpty();
        assertThat(raw).isEqualTo(original);
        assertThat(request.rawRequest()).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "stream_options"})
    void stillRejectsNonObjectNullableControls(String field) {
        var raw = mapper.createObjectNode().put("model", "qwen/qwen3.7-plus").put("input", "hello");
        raw.put(field, "invalid");

        assertThatThrownBy(() -> parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining(field + " must be an object");
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

    @Test
    void rejectsReasoningEffortAbovePublishedHighMaximum() {
        var raw = mapper.createObjectNode()
            .put("model", "qwen/qwen3.7-plus")
            .put("reasoning_effort", "max");
        raw.putArray("messages").addObject().put("role", "user").put("content", "hello");

        assertThatThrownBy(() -> parser.parse(
            CanonicalRequest.Protocol.CHAT_COMPLETIONS, route, raw))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("must be one of");
    }
}
