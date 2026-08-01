package com.any2api.provider.glm;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GlmProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reproducesTheObservedDoubleHmacContract() {
        var signature = new GlmSigner(new GlmProperties()).sign(
            "req-1", "user-1", "hello", 1785337442000L);

        assertThat(signature.signature()).isEqualTo(
            "27dcc89a89af5b8ae5760cb645286bfcd4c5c58e3d3e540e798d7798f87f3ede");
        assertThat(signature.timestamp()).isEqualTo(1785337442000L);
    }

    @Test
    void mapsResponsesInputToTheGlmChatEnvelope() {
        var raw = mapper.createObjectNode()
            .put("model", "glm/glm-5.2")
            .put("web_search", true);
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest(
            "r1",
            CanonicalRequest.Protocol.RESPONSES,
            "glm",
            "glm-5.2",
            true,
            List.of(message),
            Map.of("temperature", 0.3),
            Map.of("effort", "high"),
            List.of(),
            Map.of("preview_mode", false),
            raw);
        var requestMapper = new GlmRequestMapper(mapper);
        var seed = requestMapper.prepareChat(request, 1785337442000L);
        var completion = requestMapper.prepareCompletion(
            request, seed, "chat-1", "ticket-value", "user@example.test", 1785337442000L);

        assertThat(seed.body().path("chat").path("models").get(0).asText())
            .isEqualTo("glm-5.2");
        assertThat(completion.path("model").asText()).isEqualTo("glm-5.2");
        assertThat(completion.path("signature_prompt").asText()).isEqualTo("hello");
        assertThat(completion.path("captcha_verify_param").asText()).isEqualTo("ticket-value");
        assertThat(completion.path("features").path("auto_web_search").asBoolean()).isTrue();
        assertThat(completion.path("features").path("preview_mode").asBoolean()).isFalse();
        assertThat(completion.path("features").path("reasoning_effort").asText())
            .isEqualTo("high");
    }

    @Test
    void decodesChunkedThinkingAnswerUsageAndDoneFrames() {
        var decoder = new GlmEventDecoder("r2", mapper);
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("data: {\"type\":\"chat:completion\",\"data\":{\"phase\":\"thinking\",\"delta_"
            .getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.decode(("content\":\"why\"}}\n\n"
            + "data: {\"type\":\"chat:completion\",\"data\":{\"phase\":\"answer\",\"delta_content\":\"yes\"}}\n\n"
            + "data: {\"type\":\"chat:completion\",\"data\":{\"phase\":\"other\",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}}\n\n"
            + "data: {\"type\":\"chat:completion\",\"data\":{\"phase\":\"done\",\"done\":true}}\n\n")
            .getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Usage.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
        assertThat(events.stream().filter(CanonicalEvent.Completed.class::isInstance)).hasSize(1);
    }

    @Test
    void decodesNonSseAndAlternateAnswerPayloadsWithoutSilentlyDroppingThem() {
        var decoder = new GlmEventDecoder("r3", mapper);
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode(("{\"type\":\"chat:completion\",\"data\":"
            + "{\"phase\":\"answer\",\"content\":\"ready\"}}\n\n")
            .getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta delta
            && delta.delta().equals("ready"));
        assertThat(events).anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void classifiesNonSseJsonErrorsInsteadOfCompletingAnEmptyResponse() {
        var decoder = new GlmEventDecoder("r4", mapper);
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"error\":\"account unavailable\"}"
            .getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("provider_upstream_error"));
    }

    @Test
    void decodesOpenAiStyleAggregatedChoicesWithStructuredContent() {
        var decoder = new GlmEventDecoder("r5", mapper);
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode(("data: {\"type\":\"chat:completion\",\"data\":{"
            + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"ready\"}]}}]}}\n\n"
            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta delta
            && delta.delta().equals("ready"));
    }

    @Test
    void classifiesCompletionErrorWithoutExposingItsMessage() {
        var decoder = new GlmEventDecoder("r6", mapper);
        var events = new ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode(("data: {\"type\":\"chat:completion\",\"data\":{"
            + "\"done\":true,\"error\":{\"code\":\"account_disabled\","
            + "\"message\":\"account is unavailable\"},\"data\":{}}}\n\n"
            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("account_unavailable")
            && !failed.message().contains("account is unavailable"));
    }

    @Test
    void discoversNestedModelsAndSkipsInactiveEntries() {
        var root = mapper.readTree("""
            {"data":{"models":[
              {"id":"glm-5.2","name":"GLM 5.2","info":{"is_active":true,
                "meta":{"capabilities":{"thinking":true}}}},
              {"id":"disabled","info":{"is_active":false}}
            ]}}
            """);

        var models = GlmProvider.parseModels(root);

        assertThat(models).extracting(model -> model.id()).containsExactly("glm-5.2");
        assertThat(models.getFirst().metadata()).containsKey("glm");
    }
}
