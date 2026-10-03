package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.routing.ResolvedRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ParameterlessFunctionContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(CanonicalRequest.Protocol.class)
    void missingAndNullParametersRemainStrictThroughParserAndEventValidation(CanonicalRequest.Protocol protocol) {
        for (var explicitNull : new boolean[] {false, true}) {
            var raw = request(protocol, true, explicitNull);
            var original = raw.deepCopy();
            var canonical = new CanonicalRequestParser(mapper).parse(protocol,
                new ResolvedRoute("mimo", "fixture"), raw, "parameterless");
            var schema = StrictFunctionSchema.compile(canonical.tools().getFirst());
            assertThat(StrictFunctionSchema.accepts(schema, "{}")).isTrue();
            assertThat(StrictFunctionSchema.accepts(schema, "{\"extra\":true}")).isFalse();
            assertThat(raw).isEqualTo(original);
            var events = CanonicalEventStream.enforce(canonical, Flux.just(
                new CanonicalEvent.ResponseStarted(1, "parameterless", 0, "resp"),
                new CanonicalEvent.ToolCallStarted(1, "parameterless", 1, "call", "ping_probe"),
                new CanonicalEvent.ToolArgumentsDelta(1, "parameterless", 2, "call", "{}"),
                new CanonicalEvent.ToolCallCompleted(1, "parameterless", 3, "call", "{}"),
                new CanonicalEvent.Completed(1, "parameterless", 4, "tool_calls"))).collectList().block();
            assertThat(events).hasSize(5).noneMatch(CanonicalEvent.Failed.class::isInstance);
        }
    }

    @ParameterizedTest
    @EnumSource(CanonicalRequest.Protocol.class)
    void nonStrictDefaultsRemainOpenAndDoNotActivateValidation(CanonicalRequest.Protocol protocol) {
        var raw = request(protocol, false, false);
        var canonical = new CanonicalRequestParser(mapper).parse(protocol, new ResolvedRoute("mimo", "fixture"), raw);
        var tool = canonical.tools().getFirst();
        assertThat(tool.path("parameters").has("additionalProperties")).isFalse();
        assertThat(StrictFunctionSchema.compile(tool)).isNull();
    }

    @Test
    void explicitOpenSchemasAreNotSilentlyReplacedWithClosedDefaults() {
        var raw = request(CanonicalRequest.Protocol.RESPONSES, true, false);
        var definition = (ObjectNode) raw.path("tools").get(0);
        definition.set("parameters", mapper.createObjectNode().put("type", "object")
            .put("additionalProperties", true));
        var canonical = new CanonicalRequestParser(mapper).parse(CanonicalRequest.Protocol.RESPONSES,
            new ResolvedRoute("mimo", "fixture"), raw);
        assertThatThrownBy(() -> StrictFunctionSchema.compile(canonical.tools().getFirst()))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining("additionalProperties=false");
    }

    private ObjectNode request(CanonicalRequest.Protocol protocol, boolean strict, boolean explicitNull) {
        var raw = mapper.createObjectNode().put("model", "mimo/fixture");
        if (protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS) {
            raw.putArray("messages").addObject().put("role", "user").put("content", "Call ping_probe");
        } else raw.put("input", "Call ping_probe");
        var tool = raw.putArray("tools").addObject().put("type", "function");
        var definition = protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS ? tool.putObject("function") : tool;
        definition.put("name", "ping_probe").put("strict", strict);
        if (explicitNull) definition.putNull("parameters");
        return raw;
    }
}
