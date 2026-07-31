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

    @Test
    void rejectsFunctionToolsWhenTheProviderDoesNotDeclareThem() {
        var tool = mapper.createObjectNode().put("type", "function")
            .putObject("function").put("name", "lookup");
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(), Map.of(), Map.of(), List.of(tool),
            Map.of(), mapper.createObjectNode());
        var manifest = manifest(Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE));

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support function tools");
    }

    @Test
    void rejectsAProtocolThatTheProviderDoesNotDeclare() {
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.RESPONSES,
            "guarded", "model", false, List.of(), Map.of(), Map.of(), List.of(),
            Map.of(), mapper.createObjectNode());

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support protocol RESPONSES");
    }

    @Test
    void rejectsStoredAndStructuredResponsesWhenCapabilitiesAreAbsent() {
        var raw = mapper.createObjectNode().put("store", true);
        raw.putObject("text").putObject("format").put("type", "json_schema");
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.RESPONSES,
            "guarded", "model", false, List.of(), Map.of(), Map.of(), List.of(),
            Map.of(), raw);
        var manifest = manifest(Map.of(ProviderCapability.RESPONSES, SupportLevel.NATIVE));

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support stored Responses state");
    }

    @Test
    void rejectsUnsupportedStandardParametersInsteadOfDroppingThem() {
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(), Map.of("temperature", 0.5, "seed", 7),
            Map.of(), List.of(), Map.of(), mapper.createObjectNode());

        assertThatThrownBy(() -> ProviderRequestValidation.requireKnownGenerationParameters(
            request, java.util.Set.of("temperature")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("seed");
    }

    @Test
    void rejectsEmptyInputBeforeLeasingAnAccount() {
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(), Map.of(), Map.of(), List.of(),
            Map.of(), mapper.createObjectNode());

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("input is required");
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
