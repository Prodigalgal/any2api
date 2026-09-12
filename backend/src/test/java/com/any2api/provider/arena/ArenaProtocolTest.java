package com.any2api.provider.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.DiscoveredModel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ArenaProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsSearchToArenaNativeProviderOptionWithoutForwardingOpenAiBody() {
        var raw = mapper.createObjectNode()
            .put("model", "Max")
            .put("stream", true)
            .put("web_search", true);
        var request = new CanonicalRequest(
            "arena-request",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "arena",
            "Max",
            true,
            List.of(mapper.createObjectNode()
                .put("role", "user")
                .put("content", "find current information")),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of("web_search", true),
            raw);

        new ArenaRequestMapper().validate(request);

        assertThat(request.providerOptions()).containsEntry("web_search", true);
        assertThat(raw.has("response_format")).isFalse();
    }

    @Test
    void rejectsArenaParametersThatHaveNoNativeMapping() {
        var request = new CanonicalRequest(
            "arena-request",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "arena",
            "Max",
            true,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
            Map.of("temperature", 0.2),
            Map.of(),
            List.of(),
            Map.of(),
            mapper.createObjectNode().put("model", "Max"));

        assertThatThrownBy(() -> new ArenaRequestMapper().validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("generation");
    }

    @Test
    void rejectsBattleModeAtThePublicSemanticBoundary() {
        var request = new CanonicalRequest(
            "arena-request",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "arena",
            "Max",
            true,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of("mode", "direct-battle"),
            mapper.createObjectNode().put("model", "Max"));

        assertThatThrownBy(() -> new ArenaRequestMapper().validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("direct mode");
    }

    @Test
    void decodesTextSearchMetadataFinishAndUsageFrames() {
        var decoder = new ArenaEventDecoder("arena-request");

        var events = List.of(
            decoder.decode("f:{\"messageId\":\"arena-message\"}"),
            decoder.decode("0:\"hello\""),
            decoder.decode("2:[{\"type\":\"search\",\"text\":\"source\"}]"),
            decoder.decode("d:{\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":2,\"completionTokens\":3}}"))
            .stream()
            .flatMap(List::stream)
            .toList();

        assertThat(events).extracting(CanonicalEvent::getClass)
            .containsExactly(
                CanonicalEvent.ResponseStarted.class,
                CanonicalEvent.OutputTextDelta.class,
                CanonicalEvent.Usage.class,
                CanonicalEvent.Completed.class);
        var started = (CanonicalEvent.ResponseStarted) events.getFirst();
        assertThat(started.responseId()).isEqualTo("arena-message");
        var usage = (CanonicalEvent.Usage) events.get(2);
        assertThat(usage.inputTokens()).isEqualTo(2);
        assertThat(usage.outputTokens()).isEqualTo(3);
    }

    @Test
    void ignoresArenaMetadataFrameBeforeOutput() {
        var decoder = new ArenaEventDecoder("arena-request");

        var events = decoder.decode("a:{\"auto_resume\":false,\"click_behavior\":\"none\"}");
        events.addAll(decoder.decode("f:{\"messageId\":\"msg-1\"}"));
        events.addAll(decoder.decode("0:\"answer\""));
        events.addAll(decoder.decode("d:{\"finishReason\":\"stop\"}"));

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta);
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Completed);
        assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.Failed);
    }

    @Test
    void ignoresOfficialToolFramesAroundTextOutput() {
        var decoder = new ArenaEventDecoder("arena-request");

        var events = decoder.decode(
            "9:{\"toolCallId\":\"tool-1\",\"toolName\":\"search\",\"args\":{}}");
        events.addAll(decoder.decode(
            "a:{\"toolCallId\":\"tool-1\",\"result\":{\"status\":\"done\"}}"));
        events.addAll(decoder.decode("b:{\"toolCallId\":\"tool-2\",\"toolName\":\"search\"}"));
        events.addAll(decoder.decode("c:{\"toolCallId\":\"tool-2\",\"argsTextDelta\":\"{}\"}"));
        events.addAll(decoder.decode(
            "ac:{\"toolCallId\":\"tool-2\",\"argsTextDelta\":\"{\\\"query\\\":\\\"latest\\\"}\"}"));
        events.addAll(decoder.decode("f:{\"messageId\":\"msg-1\"}"));
        events.addAll(decoder.decode("0:\"answer\""));
        events.addAll(decoder.decode("d:{\"finishReason\":\"stop\"}"));

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta);
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Completed);
        assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.Failed);
    }

    @Test
    void decodesCurrentArenaPrefixedFrames() {
        var decoder = new ArenaEventDecoder("arena-request");

        var events = decoder.decode("a2:[{\"type\":\"routed_model\",\"organization\":\"arena\"}]");
        events.addAll(decoder.decode("a2:[{\"type\":\"heartbeat\"}]"));
        events.addAll(decoder.decode("a0:\"answer\""));
        events.addAll(decoder.decode("ad:{\"finishReason\":\"stop\"}"));

        assertThat(events).extracting(CanonicalEvent::getClass)
            .containsExactly(
                CanonicalEvent.ResponseStarted.class,
                CanonicalEvent.OutputTextDelta.class,
                CanonicalEvent.Completed.class);
        assertThat(events).noneMatch(event -> event instanceof CanonicalEvent.Failed);
    }

    @Test
    void classifiesUserNotFoundAsCredentialFailure() {
        var decoder = new ArenaEventDecoder("arena-request");

        var events = decoder.decode("3:\"User not found\"");

        assertThat(events).singleElement().isInstanceOf(CanonicalEvent.Failed.class);
        assertThat(((CanonicalEvent.Failed) events.getFirst()).errorType())
            .isEqualTo("credential_rejected");
    }

    @Test
    void classifiesPromptRateLimitAsInteractiveAntiBotChallenge() {
        var provider = new ArenaProvider(
            new ArenaProperties(),
            mock(ProxyPoolService.class),
            mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        var failure = provider.classify(new ArenaUpstreamException(
            429, "Arena upstream returned HTTP 429: {\"error\":\"prompt failed\"}"));

        assertThat(failure.type()).isEqualTo("anti_bot_rejected");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.detail()).containsEntry("challenge", "recaptcha_v2");
    }

    @Test
    void classifiesDirectApiRecaptchaValidationAsV3AntiBotChallenge() {
        var provider = new ArenaProvider(
            new ArenaProperties(),
            mock(ProxyPoolService.class),
            mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        var failure = provider.classify(new ArenaUpstreamException(
            403, "Arena upstream returned HTTP 403: {\"error\":\"recaptcha validation failed\"}"));

        assertThat(failure.type()).isEqualTo("anti_bot_rejected");
        assertThat(failure.detail()).containsEntry("challenge", "recaptcha_v3");
    }

    @Test
    void derivesPerModelMediaContractFromArenaCatalogCapabilities() {
        var provider = new ArenaProvider(
            new ArenaProperties(),
            mock(ProxyPoolService.class),
            mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));
        var model = new DiscoveredModel("Max", "Max", Map.of(
            "arena_capabilities", Map.of(
                "inputCapabilities", Map.of(
                    "text", true,
                    "image", Map.of("requiresUpload", true),
                    "file", false))));

        var contract = provider.modelContract(model);

        assertThat(contract.multimodal().input()).containsExactly("text", "image");
    }

    @Test
    void disablesBroadScheduledProbesBecauseArenaRateLimitsRealPrompts() {
        var provider = new ArenaProvider(
            new ArenaProperties(),
            mock(ProxyPoolService.class),
            mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        assertThat(provider.scheduledModelProbeEnabled()).isFalse();
    }
}
