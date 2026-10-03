package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class StrictFunctionSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsNullableNestedAndReferencedArgumentsWithoutChangingCallerSchema() {
        var definition = definition("""
            {"type":"object","additionalProperties":false,
             "properties":{"payload":{"$ref":"#/$defs/payload"},"optional":{"type":["string","null"]}},
             "required":["payload","optional"],
             "$defs":{"payload":{"type":"object","additionalProperties":false,
               "properties":{"count":{"type":"integer","minimum":1},
                 "tags":{"type":"array","minItems":1,"items":{"type":"string","enum":["a","b"]}}},
               "required":["count","tags"]}}}
            """);
        var original = definition.deepCopy();
        var schema = StrictFunctionSchema.compile(definition);
        assertThat(StrictFunctionSchema.accepts(schema,
            "{\"payload\":{\"count\":2,\"tags\":[\"a\",\"b\"]},\"optional\":null}")).isTrue();
        assertThat(definition).isEqualTo(original);
        assertThat(StrictFunctionSchema.compile(definition)).isSameAs(schema);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"count\":\"2\"}", "{\"count\":0}", "{\"count\":2,\"extra\":true}",
        "{\"count\":2} trailing", "{\"count\":2}{\"count\":3}", "{\"count\":2,\"count\":3}", "[]"
    })
    void rejectsInvalidArgumentsRatherThanCoercingOrRepairingThem(String arguments) {
        var schema = StrictFunctionSchema.compile(definition("""
            {"type":"object","additionalProperties":false,
             "properties":{"count":{"type":"integer","minimum":1}},"required":["count"]}
            """));
        assertThat(StrictFunctionSchema.accepts(schema, arguments)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"object\",\"properties\":{}}",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"string\"}}}",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"invalid\"}},\"required\":[\"value\"]}",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"array\"}},\"required\":[\"value\"]}",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{},\"$ref\":\"#/$defs/missing\"}"
    })
    void rejectsInvalidStrictSchemasDuringPreflight(String parameters) {
        assertThatThrownBy(() -> StrictFunctionSchema.compile(definition(parameters)))
            .isInstanceOf(OpenAiRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"$ref\":\"https://127.0.0.1/private\"}", "{\"$ref\":\"classpath:private\"}",
        "{\"$ref\":\"#\"}", "{\"type\":\"string\",\"pattern\":\"(a+)+$\"}",
        "{\"type\":\"string\",\"format\":\"unknown\"}"
    })
    void rejectsExternalCyclesAndUnsupportedKeywords(String child) {
        var definition = definition("""
            {"type":"object","additionalProperties":false,
             "properties":{"value":%s},"required":["value"]}
            """.formatted(child));
        assertThatThrownBy(() -> StrictFunctionSchema.compile(definition))
            .isInstanceOf(OpenAiRequestException.class)
            .satisfies(error -> assertThat(((OpenAiRequestException) error).type()).isEqualTo("unsupported_parameter"));
    }

    @Test
    void boundsSchemaSizeDepthAndArguments() {
        var oversized = definition("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");
        ((ObjectNode) oversized.path("parameters")).put("description", "x".repeat(65537));
        assertThatThrownBy(() -> StrictFunctionSchema.compile(oversized)).isInstanceOf(OpenAiRequestException.class);
        var nested = mapper.createObjectNode().put("type", "string");
        for (var index = 0; index < 34; index++) {
            var parent = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
            parent.putObject("properties").set("value", nested);
            parent.putArray("required").add("value");
            nested = parent;
        }
        var deep = mapper.createObjectNode().put("strict", true).set("parameters", nested);
        assertThatThrownBy(() -> StrictFunctionSchema.compile(deep)).isInstanceOf(OpenAiRequestException.class);
        var empty = StrictFunctionSchema.compile(mapper.createObjectNode().put("strict", true));
        assertThat(StrictFunctionSchema.accepts(empty, "{}")).isTrue();
        assertThat(StrictFunctionSchema.accepts(empty, "x".repeat(StrictFunctionSchema.MAX_ARGUMENT_BYTES + 1))).isFalse();
    }

    @Test
    void supportsBoundedAnyOfAndLeavesNonStrictSchemasUntouched() {
        var definition = definition("""
            {"type":"object","additionalProperties":false,
             "properties":{"value":{"anyOf":[{"type":"null"},{"type":"string","enum":["a"]}]}},
             "required":["value"]}
            """);
        var schema = StrictFunctionSchema.compile(definition);
        assertThat(StrictFunctionSchema.accepts(schema, "{\"value\":null}")).isTrue();
        assertThat(StrictFunctionSchema.accepts(schema, "{\"value\":\"b\"}")).isFalse();
        definition.put("strict", false);
        assertThat(StrictFunctionSchema.compile(definition)).isNull();
    }

    private ObjectNode definition(String parameters) {
        return mapper.createObjectNode().put("name", "probe").put("strict", true)
            .set("parameters", mapper.readTree(parameters));
    }
}
