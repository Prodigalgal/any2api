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
    void usesEffectiveModelCapabilitiesWhenTheyAreAvailable() {
        var request = requestWith("input_image");
        var modelCapabilities = mapper.createObjectNode();
        modelCapabilities.putObject("multimodal").putArray("input")
            .add("text").add("image");

        assertThatCode(() -> ProviderRequestValidation.requireSupportedContent(
            request, manifest(Map.of()), modelCapabilities))
            .doesNotThrowAnyException();

        var textOnly = mapper.createObjectNode();
        textOnly.putObject("multimodal").putArray("input").add("text");
        assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedContent(
            request, manifest(Map.of(ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE)),
            textOnly))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not support content block type input_image");
    }

    @Test
    void acceptsEveryCanonicalMediaShapeWhenTheCapabilityIsDeclared() {
        var cases = Map.ofEntries(
            Map.entry("image", ProviderCapability.IMAGE_INPUT),
            Map.entry("image_url", ProviderCapability.IMAGE_INPUT),
            Map.entry("input_image", ProviderCapability.IMAGE_INPUT),
            Map.entry("audio", ProviderCapability.AUDIO_INPUT),
            Map.entry("audio_url", ProviderCapability.AUDIO_INPUT),
            Map.entry("input_audio", ProviderCapability.AUDIO_INPUT),
            Map.entry("video", ProviderCapability.VIDEO_INPUT),
            Map.entry("video_url", ProviderCapability.VIDEO_INPUT),
            Map.entry("input_video", ProviderCapability.VIDEO_INPUT),
            Map.entry("attachment", ProviderCapability.FILE_INPUT),
            Map.entry("file", ProviderCapability.FILE_INPUT),
            Map.entry("input_file", ProviderCapability.FILE_INPUT));

        for (var entry : cases.entrySet()) {
            assertThatCode(() -> ProviderRequestValidation.requireSupportedContent(
                requestWith(entry.getKey()),
                manifest(Map.of(entry.getValue(), SupportLevel.NATIVE))))
                .as(entry.getKey())
                .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsMalformedMediaBlocksBeforeCallingTheRuntime() {
        var cases = Map.of(
            "audio", ProviderCapability.AUDIO_INPUT,
            "input_audio", ProviderCapability.AUDIO_INPUT,
            "video", ProviderCapability.VIDEO_INPUT,
            "input_video", ProviderCapability.VIDEO_INPUT,
            "attachment", ProviderCapability.FILE_INPUT,
            "input_file", ProviderCapability.FILE_INPUT);

        for (var entry : cases.entrySet()) {
            var message = mapper.createObjectNode().put("role", "user");
            message.putArray("content").addObject().put("type", entry.getKey());
            var request = new CanonicalRequest("malformed-" + entry.getKey(),
                CanonicalRequest.Protocol.CHAT_COMPLETIONS, "guarded", "model", false,
                List.of(message), Map.of(), Map.of(), List.of(), Map.of(),
                mapper.createObjectNode());

            assertThatThrownBy(() -> ProviderRequestValidation.requireSupportedContent(
                request, manifest(Map.of(entry.getValue(), SupportLevel.NATIVE))))
                .as(entry.getKey())
                .isInstanceOf(OpenAiRequestException.class)
                .hasMessageContaining("non-empty media source");
        }
    }

    @Test
    void rejectsNonInlineImagesBeforePageUploadProvidersLeaseAnAccount() {
        var request = requestWithImageSource("https://media.example/image.png");

        assertThatThrownBy(() -> ProviderRequestValidation.requireInlineImageUploads(
            request, "Qwen"))
            .isInstanceOf(OpenAiRequestException.class)
            .hasMessageContaining("inline base64 data URL");
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
        switch (type) {
            case "image" -> part.putObject("image").putObject("source")
                .put("data", "aGVsbG8=");
            case "image_url" -> part.put("image_url", "data:image/png;base64,aGVsbG8=");
            case "input_image" -> part.putObject("image_url")
                .put("url", "data:image/png;base64,aGVsbG8=");
            case "audio" -> part.putObject("audio").putObject("source")
                .put("data", "YQ==");
            case "audio_url" -> part.put("audio_url", "data:audio/wav;base64,YQ==");
            case "input_audio" -> part.putObject("input_audio")
                .put("data", "YQ==").put("format", "wav");
            case "video" -> part.putObject("video").putObject("source")
                .put("url", "https://media.example/video.mp4");
            case "video_url" -> part.put("video_url", "https://media.example/video.mp4");
            case "input_video" -> part.putObject("input_video")
                .put("video_url", "https://media.example/video.mp4");
            case "attachment" -> part.putObject("attachment").put("file_id", "file-1");
            case "file" -> part.putObject("file").put("file_data", "data:text/plain;base64,YQ==");
            case "input_file" -> part.putObject("input_file").put("file_id", "file-1");
            default -> { }
        }
        var message = mapper.createObjectNode().put("role", "user");
        message.putArray("content").add(part);
        return new CanonicalRequest("guard", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), mapper.createObjectNode());
    }

    private CanonicalRequest requestWithImageSource(String source) {
        var message = mapper.createObjectNode().put("role", "user");
        message.putArray("content").addObject()
            .put("type", "input_image")
            .put("image_url", source);
        return new CanonicalRequest("inline-image", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "guarded", "model", false, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), mapper.createObjectNode());
    }

    private ProviderManifest manifest(Map<ProviderCapability, SupportLevel> capabilities) {
        return new ProviderManifest("guarded", "Guarded", "test", "1",
            List.of("model"), capabilities, true);
    }
}
