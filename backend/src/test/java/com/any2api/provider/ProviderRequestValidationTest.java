package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderRequestValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsImageBlocksBeforeAnUnsupportedProviderCanDropThem() {
        var request = requestWith("image_url");
        var manifest = manifest(Map.of());

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedContent(
            request, manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support content block type image_url");
    }

    @Test
    void acceptsImageBlocksOnlyWhenTheProviderDeclaresTheCapability() {
        var request = requestWith("input_image");
        var manifest = manifest(Map.of(ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE));

        assertThatCode(() -> ProviderRequestValidation.requireSupportedContent(request, manifest))
            .doesNotThrowAnyException();
    }

    private CanonicalRequest requestWith(String type) {
        var part = mapper.createObjectNode().put("type", type);
        var message = mapper.createObjectNode().put("role", "user");
        message.putArray("content").add(part);
        return new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), mapper.createObjectNode());
    }

    private ProviderManifest manifest(Map<ProviderCapability, SupportLevel> capabilities) {
        return new ProviderManifest("guarded", "Guarded", "test", "1",
            List.of("model"), capabilities, true);
    }
}
