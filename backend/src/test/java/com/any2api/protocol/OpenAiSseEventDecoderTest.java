package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiSseEventDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesChatToolIdentityAcrossArgumentOnlyChunks() {
        var decoder = new OpenAiSseEventDecoder(mapper, "request-id");
        var events = new ArrayList<CanonicalEvent>();

        events.addAll(decoder.decode("""
            {"id":"chat-1","choices":[{"delta":{"tool_calls":[{
              "index":0,"id":"call_weather","type":"function",
              "function":{"name":"weather","arguments":"{\\\"city\\\":"}
            }]},"finish_reason":null}]}
            """));
        events.addAll(decoder.decode("""
            {"id":"chat-1","choices":[{"delta":{"tool_calls":[{
              "index":0,"function":{"arguments":"\\\"Tokyo\\\"}"}
            }]},"finish_reason":null}]}
            """));
        events.addAll(decoder.decode("""
            {"id":"chat-1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}
            """));

        assertThat(events.stream().filter(CanonicalEvent.ToolCallStarted.class::isInstance)
            .map(CanonicalEvent.ToolCallStarted.class::cast)
            .map(CanonicalEvent.ToolCallStarted::toolCallId))
            .containsExactly("call_weather");
        assertThat(events.stream().filter(CanonicalEvent.ToolCallCompleted.class::isInstance)
            .map(CanonicalEvent.ToolCallCompleted.class::cast))
            .singleElement().satisfies(completed -> {
                assertThat(completed.toolCallId()).isEqualTo("call_weather");
                assertThat(completed.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
            });
    }

    @Test
    void keepsResponsesCallIdSeparateFromOutputItemId() {
        var decoder = new OpenAiSseEventDecoder(mapper, "request-id");
        var events = new ArrayList<CanonicalEvent>();

        events.addAll(decoder.decode("""
            {"type":"response.created","response":{"id":"resp-1"}}
            """));
        events.addAll(decoder.decode("""
            {"type":"response.output_item.added","item":{
              "id":"fc-item-1","type":"function_call","call_id":"call_public_1",
              "name":"lookup","arguments":""
            }}
            """));
        events.addAll(decoder.decode("""
            {"type":"response.function_call_arguments.delta",
             "item_id":"fc-item-1","delta":"{\\\"id\\\":1}"}
            """));
        events.addAll(decoder.decode("""
            {"type":"response.output_item.done","item":{
              "id":"fc-item-1","type":"function_call","call_id":"call_public_1",
              "name":"lookup","arguments":"{\\\"id\\\":1}"
            }}
            """));
        events.addAll(decoder.decode("""
            {"type":"response.completed","response":{"id":"resp-1","usage":{
              "input_tokens":2,"output_tokens":3
            }}}
            """));

        assertThat(events.stream().filter(CanonicalEvent.ToolCallStarted.class::isInstance)
            .map(CanonicalEvent.ToolCallStarted.class::cast)
            .map(CanonicalEvent.ToolCallStarted::toolCallId))
            .containsExactly("call_public_1");
        assertThat(events.stream().filter(CanonicalEvent.ToolCallCompleted.class::isInstance)
            .map(CanonicalEvent.ToolCallCompleted.class::cast)
            .map(CanonicalEvent.ToolCallCompleted::toolCallId))
            .containsExactly("call_public_1");
    }

    @Test
    void convertsAnUnterminatedUpstreamStreamIntoCanonicalFailure() {
        var decoder = new OpenAiSseEventDecoder(mapper, "request-id");

        decoder.decode("""
            {"type":"response.created","response":{"id":"resp-1"}}
            """);

        assertThat(decoder.finish())
            .singleElement()
            .isInstanceOfSatisfying(CanonicalEvent.Failed.class, failed ->
                assertThat(failed.errorType()).isEqualTo("incomplete_upstream_stream"));
    }
}
