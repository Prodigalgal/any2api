package com.any2api.provider.grok_web;

import com.any2api.transport.BrowserTransportClient;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GrokWebStreamPreflight {
    private static final int MAX_PREFLIGHT_BYTES = 1 << 20;
    private final ObjectMapper mapper;
    private final JsonObjectStreamFramer frames;
    private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
    private boolean accepted;

    GrokWebStreamPreflight(ObjectMapper mapper) {
        this.mapper = mapper;
        frames = new JsonObjectStreamFramer(mapper);
    }

    List<byte[]> accept(byte[] chunk) {
        if (accepted) return List.of(chunk);
        buffered.writeBytes(chunk);
        if (buffered.size() > MAX_PREFLIGHT_BYTES) {
            throw new IllegalArgumentException("Grok Web response preflight exceeded 1 MiB");
        }
        for (var frame : frames.decode(chunk)) {
            var error = error(frame);
            if (error.isObject()) {
                var code = error.path("code").asText("");
                var message = error.path("message").asText(
                    error.path("error").asText("Grok Web request failed"));
                if ("7".equals(code) || message.toLowerCase().contains("anti-bot")
                    || message.toLowerCase().contains("blocked-user")) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        403, mapper.writeValueAsString(error));
                }
            }
            if (decisive(frame)) {
                accepted = true;
                return List.of(buffered.toByteArray());
            }
        }
        return List.of();
    }

    List<byte[]> finish() {
        if (accepted || buffered.size() == 0) return List.of();
        frames.finish();
        accepted = true;
        return List.of(buffered.toByteArray());
    }

    private JsonNode error(JsonNode frame) {
        if (frame.path("error").isObject()) return frame.path("error");
        var result = frame.path("result");
        if (result.path("error").isObject()) return result.path("error");
        return result.path("response").path("error");
    }

    private boolean decisive(JsonNode frame) {
        var result = frame.path("result");
        var response = result.path("response");
        if (!response.isObject()) response = result;
        return response.isObject() && (
            response.has("token") || response.has("modelResponse")
                || response.has("streamingImageGenerationResponse")
                || response.has("streamingVideoGenerationResponse")
                || response.has("messageTag") || response.has("error"));
    }
}
