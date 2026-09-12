package com.any2api.provider.glm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.coordination.AccountLease;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class GlmProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowsBrowserInitializationAndSlowCompletionDuringRuntimeProbes() {
        var properties = new GlmProperties();
        var provider = new GlmProvider(
            properties,
            mock(ProxyPoolService.class),
            mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        assertThat(provider.modelProbeTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(provider.accountProbeTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThatThrownBy(() -> properties.setModelProbeTimeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }

    @Test
    void stopsWhenProviderEmitsTerminalEventEvenIfBrowserStreamStaysOpen() {
        var transport = mock(OfficialBrowserTransportClient.class);
        var semanticCommands = mock(OfficialBrowserSemanticCommandFactory.class);
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper, transport, semanticCommands);
        var request = new CanonicalRequest(
            "glm-terminal", CanonicalRequest.Protocol.CHAT_COMPLETIONS, "glm", "glm-5.2",
            false,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
        var accountId = UUID.randomUUID();
        var account = new LeasedProviderAccount(
            accountId, "glm", "external", "user@example.test", 1, null,
            mapper.createObjectNode().put("token", "token").put("user_id", "user"), Map.of(),
            new AccountLease("glm", accountId, "owner", 1, Instant.now().plusSeconds(60)));
        var context = new com.any2api.provider.ProviderExecutionContext(
            request.requestId(), accountId, "1", "owner", 1, Instant.now().plusSeconds(60));
        var status = mapper.createObjectNode().put("type", "status").put("status", 200);
        var data = mapper.createObjectNode().put("type", "data").put("data",
            "data: {\"type\":\"chat:completion\",\"data\":{"
                + "\"phase\":\"answer\",\"delta_content\":\"ok\"}}\n\n"
                + "data: {\"type\":\"chat:completion\",\"data\":{"
                + "\"phase\":\"done\",\"done\":true}}\n\n");
        when(semanticCommands.chat(any())).thenReturn(mapper.createObjectNode());
        when(transport.stream(anyString(), anyString(), any(), any(), anyMap(), anyString()))
            .thenReturn(Flux.concat(Flux.just(status, data), Flux.never()));

        var events = provider.generate(request, context, account)
            .collectList()
            .block(Duration.ofSeconds(2));

        assertThat(events).isNotNull()
            .anyMatch(CanonicalEvent.Completed.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta delta
                && delta.delta().equals("ok"));
    }

    @Test
    void restoresSseBoundariesForNormalizedApiActionFrames() {
        var transport = mock(OfficialBrowserTransportClient.class);
        var semanticCommands = mock(OfficialBrowserSemanticCommandFactory.class);
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper, transport, semanticCommands);
        var request = new CanonicalRequest(
            "glm-api-frame", CanonicalRequest.Protocol.CHAT_COMPLETIONS, "glm", "glm-5.2",
            false,
            List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
        var accountId = UUID.randomUUID();
        var account = new LeasedProviderAccount(
            accountId, "glm", "external", "user@example.test", 1, null,
            mapper.createObjectNode().put("token", "token").put("user_id", "user"), Map.of(),
            new AccountLease("glm", accountId, "owner", 1, Instant.now().plusSeconds(60)));
        var context = new com.any2api.provider.ProviderExecutionContext(
            request.requestId(), accountId, "1", "owner", 1, Instant.now().plusSeconds(60),
            com.any2api.provider.ProviderTransportMode.API);
        var status = mapper.createObjectNode().put("type", "status").put("status", 200);
        var answer = mapper.createObjectNode().put("type", "data").put("data",
            "{\"type\":\"chat:completion\",\"data\":{"
                + "\"phase\":\"answer\",\"delta_content\":\"ok\"}}");
        var done = mapper.createObjectNode().put("type", "data").put("data",
            "{\"type\":\"chat:completion\",\"data\":{"
                + "\"phase\":\"done\",\"done\":true}}");
        when(semanticCommands.chat(any())).thenReturn(mapper.createObjectNode());
        when(transport.stream(anyString(), anyString(), any(), any(), anyMap(), anyString(),
            anyMap(), eq(com.any2api.provider.ProviderTransportMode.API)))
            .thenReturn(Flux.concat(Flux.just(status, answer, done), Flux.never()));

        var events = provider.generate(request, context, account)
            .collectList()
            .block(Duration.ofSeconds(2));

        assertThat(events).isNotNull()
            .anyMatch(CanonicalEvent.Completed.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta delta
                && delta.delta().equals("ok"));
    }

    @Test
    void exposesVisionOnlyForModelsThatOfficialMetadataMarksAsVisionCapable() throws Exception {
        var root = mapper.readTree("""
            {"data":{"models":[
              {"id":"glm-4.6v","info":{"meta":{"capabilities":{"vision":true}}}},
              {"id":"glm-5.2","info":{"meta":{"capabilities":{"vision":false}}}}
            ]}}
            """);
        var models = GlmProvider.parseModels(root);
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        assertThat(provider.modelContract(models.get(0)).multimodal().input())
            .containsExactly("text", "image");
        assertThat(provider.modelContract(models.get(1)).multimodal().input())
            .containsExactly("text");
    }

    @Test
    void classifiesAliyunCaptchaAsRuntimeFallbackCandidate() {
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        var failure = provider.classify(
            new GlmUpstreamException(403, "aliyun captcha required"));

        assertThat(failure.type()).isEqualTo("anti_bot_rejected");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void classifiesExplicitAntiBotCodeEvenWhenUpstreamUsesHttp400() {
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        var failure = provider.classify(new GlmUpstreamException(
            400, "GLM upstream returned anti_bot_rejected"));

        assertThat(failure.type()).isEqualTo("anti_bot_rejected");
        assertThat(failure.detail())
            .containsEntry("challenge", "provider_verification");
    }

    @Test
    void exposesOnlyTheTypedProviderIssuedCaptchaOption() {
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        assertThat(provider.protocolContract().providerOptions())
            .containsEntry("captcha_verify_param", ProviderProtocolContract.OptionType.STRING);
    }

    @Test
    void disablesBroadScheduledProbesThatWouldRunWithoutProviderVerification() {
        var provider = new GlmProvider(
            new GlmProperties(), mock(ProxyPoolService.class), mapper,
            mock(OfficialBrowserTransportClient.class),
            mock(OfficialBrowserSemanticCommandFactory.class));

        assertThat(provider.scheduledModelProbeEnabled()).isFalse();
    }
}
