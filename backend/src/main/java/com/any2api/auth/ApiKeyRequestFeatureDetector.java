package com.any2api.auth;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class ApiKeyRequestFeatureDetector {
    private static final Set<String> MULTIMODAL_TYPES = Set.of(
        "image", "image_url", "input_image", "input_audio", "audio", "video", "input_video");
    private static final Set<String> FILE_TYPES = Set.of(
        "file", "input_file", "file_url", "attachment");

    public Set<ApiKeyFeature> requiredFeatures(ObjectNode request) {
        var required = new LinkedHashSet<ApiKeyFeature>();
        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            required.add(ApiKeyFeature.TOOL_CALLING);
        }
        inspect(request.path("messages"), required);
        inspect(request.path("input"), required);
        var attachments = request.path("attachments");
        if (attachments.isArray() && !attachments.isEmpty()) {
            required.add(ApiKeyFeature.FILE_UPLOADS);
        }
        inspect(attachments, required);
        return Set.copyOf(required);
    }

    private void inspect(JsonNode node, Set<ApiKeyFeature> required) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(value -> inspect(value, required));
            return;
        }
        if (!node.isObject()) return;

        var type = node.path("type").asText("").trim().toLowerCase();
        if (MULTIMODAL_TYPES.contains(type)) {
            required.add(ApiKeyFeature.MULTIMODAL_INPUT);
        }
        if (FILE_TYPES.contains(type)) {
            required.add(ApiKeyFeature.FILE_UPLOADS);
        }
        node.properties().forEach(entry -> {
            if (containsInlineFile(entry.getValue())) {
                required.add(ApiKeyFeature.FILE_UPLOADS);
            }
            inspect(entry.getValue(), required);
        });
    }

    private boolean containsInlineFile(JsonNode value) {
        if (value.isTextual()) {
            return value.asText().regionMatches(true, 0, "data:", 0, 5);
        }
        if (!value.isObject()) return false;
        var url = value.path("url");
        return url.isTextual() && url.asText().regionMatches(true, 0, "data:", 0, 5);
    }
}
