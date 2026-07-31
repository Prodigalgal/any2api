package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import java.util.Set;

public final class ProviderRequestValidation {
    private ProviderRequestValidation() {
    }

    public static void requireKnownOptions(CanonicalRequest request, Set<String> allowed) {
        var unknown = request.providerOptions().keySet().stream()
            .filter(key -> !allowed.contains(key))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "unsupported provider_options for " + request.providerId() + ": "
                    + String.join(", ", unknown));
        }
    }

    public static void requireKnownGenerationParameters(
        CanonicalRequest request,
        Set<String> supported
    ) {
        var unsupported = request.generation().keySet().stream()
            .filter(key -> !"stream_options".equals(key))
            .filter(key -> !supported.contains(key))
            .sorted()
            .toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                "unsupported standard parameters for " + request.providerId() + ": "
                    + String.join(", ", unsupported));
        }
    }

    public static void requireSupportedContent(
        CanonicalRequest request,
        ProviderManifest manifest
    ) {
        for (var message : request.messages()) {
            var content = message.path("content");
            if (!content.isArray()) continue;
            for (var part : content) {
                var type = part.path("type").asText("");
                var capability = switch (type) {
                    case "image_url", "input_image" -> ProviderCapability.IMAGE_INPUT;
                    case "input_audio" -> ProviderCapability.AUDIO_INPUT;
                    case "video_url", "input_video" -> ProviderCapability.VIDEO_INPUT;
                    case "file", "input_file" -> ProviderCapability.FILE_INPUT;
                    default -> null;
                };
                if (capability != null && manifest.capabilities()
                    .getOrDefault(capability, SupportLevel.UNSUPPORTED)
                    == SupportLevel.UNSUPPORTED) {
                    throw new IllegalArgumentException(
                        manifest.id() + " does not support content block type " + type);
                }
            }
        }
    }

    public static void requireSupportedRequest(
        CanonicalRequest request,
        ProviderManifest manifest
    ) {
        var protocolCapability = request.protocol() == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ProviderCapability.CHAT_COMPLETIONS : ProviderCapability.RESPONSES;
        requireCapability(manifest, protocolCapability, "protocol " + request.protocol());
        requireSupportedContent(request, manifest);

        var hasFunctionTools = request.tools().stream().anyMatch(tool ->
            "function".equals(tool.path("type").asText("function")));
        if (hasFunctionTools) {
            requireCapability(manifest, ProviderCapability.FUNCTION_TOOLS, "function tools");
        }
        if (request.protocol() == CanonicalRequest.Protocol.RESPONSES) {
            var raw = request.rawRequest();
            if (raw.path("store").asBoolean(false)
                || !raw.path("previous_response_id").asText("").isBlank()) {
                requireCapability(manifest, ProviderCapability.STORED_RESPONSES,
                    "stored Responses state");
            }
            var format = raw.path("text").path("format").path("type").asText("");
            if (Set.of("json_object", "json_schema").contains(format)) {
                requireCapability(manifest, ProviderCapability.STRUCTURED_OUTPUT,
                    "structured output");
            }
        } else {
            var format = request.rawRequest().path("response_format").path("type").asText("");
            if (Set.of("json_object", "json_schema").contains(format)) {
                requireCapability(manifest, ProviderCapability.STRUCTURED_OUTPUT,
                    "structured output");
            }
        }
    }

    private static void requireCapability(
        ProviderManifest manifest,
        ProviderCapability capability,
        String feature
    ) {
        if (manifest.capabilities().getOrDefault(capability, SupportLevel.UNSUPPORTED)
            == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException(
                manifest.id() + " does not support " + feature);
        }
    }
}
