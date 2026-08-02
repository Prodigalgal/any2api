package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public record ProviderProtocolContract(
    Map<String, OptionType> providerOptions,
    Set<String> chatParameters,
    Set<String> responsesParameters,
    Set<String> toolTypes,
    Set<String> reasoningParameters
) {
    public ProviderProtocolContract {
        providerOptions = providerOptions == null ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(providerOptions));
        chatParameters = immutable(chatParameters);
        responsesParameters = immutable(responsesParameters);
        toolTypes = immutable(toolTypes);
        reasoningParameters = immutable(reasoningParameters);
    }

    public static ProviderProtocolContract strict() {
        return new ProviderProtocolContract(
            Map.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public ProviderProtocolContract(
        Set<String> providerOptions,
        Set<String> chatParameters,
        Set<String> responsesParameters,
        Set<String> toolTypes
    ) {
        this(providerOptions == null ? Map.of() : providerOptions.stream().collect(
                Collectors.toUnmodifiableMap(name -> name, ignored -> OptionType.ANY)),
            chatParameters, responsesParameters, toolTypes,
            defaultReasoningParameters(chatParameters, responsesParameters));
    }

    public ProviderProtocolContract(
        Map<String, OptionType> providerOptions,
        Set<String> chatParameters,
        Set<String> responsesParameters,
        Set<String> toolTypes
    ) {
        this(providerOptions, chatParameters, responsesParameters, toolTypes,
            defaultReasoningParameters(chatParameters, responsesParameters));
    }

    public Set<String> parameters(CanonicalRequest.Protocol protocol) {
        return protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? chatParameters : responsesParameters;
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of()
            : Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static Set<String> defaultReasoningParameters(
        Set<String> chatParameters,
        Set<String> responsesParameters
    ) {
        return chatParameters != null && chatParameters.contains("reasoning")
            || responsesParameters != null && responsesParameters.contains("reasoning")
            ? Set.of("effort") : Set.of();
    }

    public enum OptionType {
        BOOLEAN,
        STRING,
        NUMBER,
        INTEGER,
        OBJECT,
        ARRAY,
        ANY;

        public boolean accepts(JsonNode value) {
            return switch (this) {
                case BOOLEAN -> value.isBoolean();
                case STRING -> value.isTextual();
                case NUMBER -> value.isNumber();
                case INTEGER -> value.isIntegralNumber();
                case OBJECT -> value.isObject();
                case ARRAY -> value.isArray();
                case ANY -> true;
            };
        }
    }
}
