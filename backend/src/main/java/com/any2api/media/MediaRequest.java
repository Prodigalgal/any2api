package com.any2api.media;

import java.util.Map;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record MediaRequest(
    String requestId,
    String providerId,
    String model,
    MediaOperation operation,
    String prompt,
    int count,
    ResponseFormat responseFormat,
    List<MediaInput> inputs,
    Map<String, Object> options,
    JsonNode rawRequest
) {
    public MediaRequest {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public MediaRequest(
        String requestId,
        String providerId,
        String model,
        MediaOperation operation,
        String prompt,
        int count,
        ResponseFormat responseFormat,
        Map<String, Object> options,
        JsonNode rawRequest
    ) {
        this(requestId, providerId, model, operation, prompt, count,
            responseFormat, List.of(), options, rawRequest);
    }

    public enum ResponseFormat {
        URL,
        B64_JSON
    }
}
