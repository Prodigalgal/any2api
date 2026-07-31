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
}
