package com.any2api.provider.grok_web;

import java.util.LinkedHashSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GrokWebImageEventDecoder {
    private final JsonObjectStreamFramer frames;
    private final LinkedHashSet<String> urls = new LinkedHashSet<>();

    GrokWebImageEventDecoder(ObjectMapper mapper) {
        frames = new JsonObjectStreamFramer(mapper);
    }

    boolean decode(byte[] chunk) {
        for (var frame : frames.decode(chunk)) parse(frame);
        return !urls.isEmpty();
    }

    String finish() {
        frames.finish();
        return urls.stream().findFirst().orElseThrow(() ->
            new IllegalStateException("Grok Web image response did not contain a final image"));
    }

    private void parse(JsonNode root) {
        if (root.path("error").isObject()) throw upstreamError(root.path("error"));
        var result = root.path("result");
        var response = result.path("response");
        if (!response.isObject()) response = result;
        if (!response.isObject()) return;
        if (response.path("error").isObject()) throw upstreamError(response.path("error"));
        appendStreaming(response.path("streamingImageGenerationResponse"));
        var model = response.path("modelResponse");
        for (var url : model.path("generatedImageUrls")) append(url.asText(""));
        for (var url : response.path("generatedImageUrls")) append(url.asText(""));
    }

    private void appendStreaming(JsonNode image) {
        if (!image.isObject() || image.path("moderated").asBoolean(false)) return;
        if (image.path("isFinal").asBoolean(false) || image.path("progress").asInt(0) == 100) {
            append(image.path("imageUrl").asText(image.path("url").asText("")));
        }
    }

    private void append(String value) {
        if (!value.isBlank()) urls.add(value.trim());
    }

    private RuntimeException upstreamError(JsonNode error) {
        return new GrokWebEventDecoder.GrokWebStreamException(
            error.path("code").asText("provider_error"),
            error.path("message").asText("Grok Web image generation failed"));
    }
}
