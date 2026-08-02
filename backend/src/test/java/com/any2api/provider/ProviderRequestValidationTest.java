package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void rejectsStreamingWhenTheProviderDoesNotDeclareIt() {
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.RESPONSES,
            "guarded", "model", true, List.of(message), Map.of(), Map.of(), List.of(),
            Map.of(), mapper.createObjectNode().put("stream", true));

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.RESPONSES, SupportLevel.NATIVE))))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("does not support streaming");
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

    @Test
    void rejectsAStandardFieldThatTheProviderDoesNotTranslate() {
        var raw = mapper.createObjectNode().put("model", "guarded/model").put("seed", 7);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        raw.putArray("messages").add(message);
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of("seed", 7), Map.of(),
            List.of(), Map.of(), raw);

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE)),
            ProviderProtocolContract.strict()))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("not translated");
    }

    @Test
    void acceptsOnlyFieldsDeclaredByTheProviderContract() {
        var raw = mapper.createObjectNode().put("model", "guarded/model").put("temperature", 0.4);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        raw.putArray("messages").add(message);
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of("temperature", 0.4), Map.of(),
            List.of(), Map.of(), raw);
        var contract = new ProviderProtocolContract(
            Set.of(), Set.of("temperature"), Set.of(), Set.of());

        assertThatCode(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE)),
            contract)).doesNotThrowAnyException();
    }

    @Test
    void rejectsProviderOptionWithTheWrongDeclaredType() {
        var raw = mapper.createObjectNode().put("model", "guarded/model");
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        raw.putArray("messages").add(message);
        raw.putObject("provider_options").putObject("guarded").put("web_search", "yes");
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(), List.of(),
            Map.of("web_search", "yes"), raw);
        var contract = new ProviderProtocolContract(
            Map.of("web_search", ProviderProtocolContract.OptionType.BOOLEAN),
            Set.of(), Set.of(), Set.of());

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE)),
            contract))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("must be boolean");
    }

    @Test
    void rejectsReasoningSubfieldsThatTheProviderDoesNotTranslate() {
        var raw = mapper.createObjectNode().put("model", "guarded/model");
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        raw.putArray("messages").add(message);
        raw.putObject("reasoning").put("effort", "high").put("summary", "detailed");
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(),
            Map.of("effort", "high", "summary", "detailed"), List.of(), Map.of(), raw);
        var contract = new ProviderProtocolContract(
            Map.of(), Set.of("reasoning"), Set.of(), Set.of());

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(
                ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
                ProviderCapability.REASONING, SupportLevel.NATIVE)), contract))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("reasoning field is not translated")
            .hasMessageContaining("summary");
    }

    @Test
    void rejectsMalformedContentAndFunctionToolsBeforeProviderMapping() {
        var message = mapper.createObjectNode().put("role", "user");
        message.putArray("content").addObject().put("type", "input_text");
        var tool = mapper.createObjectNode().put("type", "function");
        tool.putObject("function").put("name", "lookup")
            .putObject("parameters").put("type", "object");
        var raw = mapper.createObjectNode().put("model", "guarded/model");
        raw.putArray("messages").add(message);
        raw.putArray("tools").add(tool);
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(), List.of(tool),
            Map.of(), raw);
        var contract = new ProviderProtocolContract(
            Map.of(), Set.of("tools"), Set.of(), Set.of("function"));
        var manifest = manifest(Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE));

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest, contract))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("text field");
    }

    @Test
    void rejectsFunctionToolsWithoutNames() {
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var tool = mapper.createObjectNode().put("type", "function");
        tool.putObject("function").putObject("parameters").put("type", "object");
        var raw = mapper.createObjectNode().put("model", "guarded/model");
        raw.putArray("messages").add(message);
        raw.putArray("tools").add(tool);
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(), List.of(tool),
            Map.of(), raw);
        var contract = new ProviderProtocolContract(
            Map.of(), Set.of("tools"), Set.of(), Set.of("function"));
        var manifest = manifest(Map.of(
            ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE,
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE));

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest, contract))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("require a name");
    }

    @Test
    void rejectsToolMessagesWithoutCallIdentity() {
        var message = mapper.createObjectNode().put("role", "tool").put("content", "result");
        var raw = mapper.createObjectNode().put("model", "guarded/model");
        raw.putArray("messages").add(message);
        var request = new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(), List.of(),
            Map.of(), raw);

        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedRequest(
            request, manifest(Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE)),
            ProviderProtocolContract.strict()))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("tool_call_id");
    }

    private CanonicalRequest requestWith(String type) {
        var part = mapper.createObjectNode().put("type", type);
        if (Set.of("image_url", "input_image").contains(type)) {
            part.put("image_url", "data:image/png;base64,aGVsbG8=");
        }
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
