package com.any2api.provider.longcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LongcatProtocolTest {
    @Test
    void seedsProviderCookiesIntoTheOpaqueTransportJar() {
        assertThat(new LongcatCredential(
            "passport_token_key=session; _lxsdk_cuid=device; ignored").cookies())
            .containsEntry("passport_token_key", "session")
            .containsEntry("_lxsdk_cuid", "device")
            .doesNotContainKey("ignored");
    }

    @Test
    void mapsProviderDialectAndDecodesReasoningAndFinish() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "longcat/longcat-flash")
            .put("reason_enabled", true).put("search_enabled", true);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("r2", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "longcat", "longcat-flash", true, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);
        var tools = new LongcatToolProtocol(mapper);
        var prepared = new LongcatRequestMapper(mapper, tools).prepare(request);
        var decoder = new LongcatEventDecoder("r2", true, prepared.toolPlan(), tools);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"event\":{\"type\":\"think\",\"content\":\"why\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"content\",\"content\":\"answer\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"finish\",\"usage\":{\"inputTokens\":2,\"outputTokens\":1}},\"lastOne\":true}"));

        assertThat(prepared.reasonEnabled()).isTrue();
        assertThat(prepared.searchEnabled()).isTrue();
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void separatesPureThinkingContentFromTheFinalAnswer() {
        var mapper = new ObjectMapper();
        var tools = new LongcatToolProtocol(mapper);
        var plan = tools.plan(request(mapper, mapper.createArrayNode(), mapper.createObjectNode()));
        var decoder = new LongcatEventDecoder("thinking", true, plan, tools);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"event\":{\"type\":\"content\",\"content\":\"step one\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"content\",\"content\":\"step two\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"finish\",\"finalContent\":\"answer\"}}"));

        assertThat(events.stream().filter(CanonicalEvent.ReasoningDelta.class::isInstance)
            .map(CanonicalEvent.ReasoningDelta.class::cast)
            .map(CanonicalEvent.ReasoningDelta::delta)).contains("step onestep two");
        assertThat(events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta)).containsExactly("answer");
    }

    @Test
    void portsTheLegacyJsonToolContractForChatAndResponses() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var rawTools = mapper.createArrayNode();
        var function = rawTools.addObject().put("type", "function").putObject("function");
        function.put("name", "get_weather").put("description", "weather")
            .putObject("parameters").put("type", "object");
        var request = request(mapper, rawTools, raw);
        var tools = new LongcatToolProtocol(mapper);
        var prepared = new LongcatRequestMapper(mapper, tools).prepare(request);
        var decoder = new LongcatEventDecoder("tools", false, prepared.toolPlan(), tools);
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"event\":{\"type\":\"content\",\"content\":"
            + "\"{\\\"tool_calls\\\":[{\\\"name\\\":\\\"get_weather\\\","
            + "\\\"arguments\\\":{\\\"city\\\":\\\"Xiamen\\\"}}]}\"}}"));
        events.addAll(decoder.decode("{\"event\":{\"type\":\"finish\"}}"));

        assertThat(prepared.content()).contains("[Tool calling contract]")
            .contains("get_weather");
        assertThat(events).anyMatch(CanonicalEvent.ToolCallStarted.class::isInstance)
            .anyMatch(CanonicalEvent.ToolArgumentsDelta.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));
        assertThat(events).noneMatch(CanonicalEvent.OutputTextDelta.class::isInstance);
    }

    @Test
    void rejectsAnEmptyLongcatStream() {
        var mapper = new ObjectMapper();
        var tools = new LongcatToolProtocol(mapper);
        var plan = tools.plan(request(mapper, mapper.createArrayNode(), mapper.createObjectNode()));

        var events = new LongcatEventDecoder("empty", false, plan, tools).finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }

    private CanonicalRequest request(
        ObjectMapper mapper,
        tools.jackson.databind.JsonNode toolNodes,
        tools.jackson.databind.node.ObjectNode raw
    ) {
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var values = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        toolNodes.forEach(values::add);
        return new CanonicalRequest("test", CanonicalRequest.Protocol.RESPONSES,
            "longcat", "longcat-pro", true, List.of(message), Map.of(), Map.of(),
            List.copyOf(values), Map.of(), raw);
    }
}
