package com.any2api.protocol;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.OutputFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded local schema validation for caller-owned strict function arguments. */
public final class StrictFunctionSchema {
    public static final int MAX_ARGUMENT_BYTES = 1 << 20;
    private static final int MAX_SCHEMA_BYTES = 1 << 16;
    private static final int MAX_SCHEMA_DEPTH = 32;
    private static final int MAX_SCHEMA_NODES = 2048;
    private static final Set<String> KEYWORDS = Set.of(
        "type", "properties", "required", "additionalProperties", "items", "$defs", "definitions", "$ref",
        "anyOf", "enum", "const", "description", "title", "default", "minimum", "maximum",
        "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "minLength", "maxLength");
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Cache<String, Schema> SCHEMAS = Caffeine.newBuilder()
        .maximumSize(128).expireAfterAccess(Duration.ofMinutes(10)).build();

    private StrictFunctionSchema() {}

    private static final class SchemaResources {
        // Non-strict requests and invalid preflight schemas do not need the bundled schema registry.
        private static final SchemaRegistry REGISTRY = SchemaRegistry.withDialect(Dialects.getDraft202012());
        private static final Schema META_SCHEMA = REGISTRY.getSchema(
            SchemaLocation.of(Dialects.getDraft202012().getId()));
    }

    public static Schema compile(JsonNode definition) {
        if (!definition.path("strict").asBoolean(false)) return null;
        var parameters = definition.hasNonNull("parameters") ? definition.path("parameters")
            : OpenAiToolBridge.emptyFunctionParameters(true);
        var encoded = parameters.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict function schema exceeds 64 KiB");
        }
        return SCHEMAS.get(encoded, ignored -> compileParameters(parameters));
    }

    private static Schema compileParameters(JsonNode parameters) {
        if (!parameters.isObject() || !"object".equals(parameters.path("type").asText())) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict function parameters must have type object");
        }
        inspect(parameters, parameters, 0, new int[1], new HashSet<>());
        if (!SchemaResources.META_SCHEMA.validate(parameters, OutputFormat.BOOLEAN)) {
            throw OpenAiRequestException.invalid("tools.parameters", "invalid strict function JSON schema");
        }
        try {
            return SchemaResources.REGISTRY.getSchema(parameters);
        } catch (RuntimeException cause) {
            var error = OpenAiRequestException.invalid("tools.parameters", "invalid strict function JSON schema");
            error.initCause(cause);
            throw error;
        }
    }

    private static void inspect(JsonNode schema, JsonNode root, int depth, int[] nodes, Set<String> references) {
        if (depth > MAX_SCHEMA_DEPTH || ++nodes[0] > MAX_SCHEMA_NODES) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict schema exceeds depth or expansion limit");
        }
        if (!schema.isObject()) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict schema nodes must be objects");
        }
        for (var keyword : schema.propertyNames()) {
            if (!KEYWORDS.contains(keyword)) {
                throw OpenAiRequestException.unsupported("tools.parameters." + keyword,
                    "unsupported strict schema keyword: " + keyword);
            }
        }
        if (schema.has("$ref")) inspectReference(schema.path("$ref"), root, depth, nodes, references);
        if (hasType(schema, "object") || schema.has("properties")) inspectObject(schema);
        for (var keyword : Set.of("properties", "$defs", "definitions")) {
            if (schema.has(keyword)) {
                if (!schema.path(keyword).isObject()) {
                    throw OpenAiRequestException.invalid("tools.parameters", keyword + " must be an object");
                }
                for (var child : schema.path(keyword)) inspect(child, root, depth + 1, nodes, references);
            }
        }
        if (schema.has("items")) inspect(schema.path("items"), root, depth + 1, nodes, references);
        if (hasType(schema, "array") && !schema.has("items")) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict arrays require an items schema");
        }
        if (schema.has("anyOf")) {
            if (!schema.path("anyOf").isArray() || schema.path("anyOf").isEmpty()
                || schema.path("anyOf").size() > 16) {
                throw OpenAiRequestException.invalid("tools.parameters", "anyOf requires 1..16 schemas");
            }
            for (var child : schema.path("anyOf")) inspect(child, root, depth + 1, nodes, references);
        }
    }

    private static void inspectReference(JsonNode reference, JsonNode root, int depth,
        int[] nodes, Set<String> references) {
        var pointer = reference.asText("");
        if (!reference.isTextual() || !(pointer.equals("#") || pointer.startsWith("#/"))) {
            throw OpenAiRequestException.unsupported("tools.parameters.$ref", "only local schema references are supported");
        }
        if (!references.add(pointer)) {
            throw OpenAiRequestException.unsupported("tools.parameters.$ref", "cyclic strict schema references are unsupported");
        }
        try {
            var target = pointer.equals("#") ? root : root.at(pointer.substring(1));
            if (target.isMissingNode()) {
                throw OpenAiRequestException.invalid("tools.parameters.$ref", "schema reference does not exist");
            }
            inspect(target, root, depth + 1, nodes, references);
        } finally {
            references.remove(pointer);
        }
    }

    private static void inspectObject(JsonNode schema) {
        if (!schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean()) {
            throw OpenAiRequestException.invalid("tools.parameters", "strict objects require additionalProperties=false");
        }
        var properties = schema.path("properties");
        var required = schema.path("required");
        var names = new HashSet<String>();
        if (required.isArray()) for (var name : required) {
            if (!name.isTextual() || !names.add(name.asText())) {
                throw OpenAiRequestException.invalid("tools.parameters", "required must contain unique property names");
            }
        }
        if ((!required.isMissingNode() && !required.isArray())
            || !names.equals(properties.isObject() ? properties.propertyNames() : Set.of())) {
            throw OpenAiRequestException.invalid("tools.parameters", "all strict object properties must be required");
        }
    }

    private static boolean hasType(JsonNode schema, String name) {
        var type = schema.path("type");
        if (type.isTextual()) return name.equals(type.asText());
        if (type.isArray()) for (var value : type) if (name.equals(value.asText())) return true;
        return false;
    }

    public static boolean accepts(Schema schema, String arguments) {
        if (arguments == null || arguments.length() > MAX_ARGUMENT_BYTES
            || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return false;
        try {
            var value = MAPPER.readTree(arguments);
            return value != null && value.isObject() && schema.validate(value, OutputFormat.BOOLEAN);
        } catch (RuntimeException error) {
            return false; // The caller converts invalid upstream arguments into an explicit failed event.
        }
    }
}
