package com.any2api.protocol;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record CanonicalRequest(
    String requestId,
    Protocol protocol,
    String providerId,
    String model,
    boolean stream,
    List<JsonNode> messages,
    Map<String, Object> generation,
    Map<String, Object> reasoning,
    List<JsonNode> tools,
    Map<String, Object> providerOptions,
    JsonNode rawRequest
) {
    public enum Protocol {
        CHAT_COMPLETIONS,
        RESPONSES
    }
}
