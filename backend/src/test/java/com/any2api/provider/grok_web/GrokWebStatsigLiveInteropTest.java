package com.any2api.provider.grok_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.any2api.config.Any2ApiProperties;
import com.any2api.lifecycle.AutomationOperation;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.BrowserClearanceCoordinator;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class GrokWebStatsigLiveInteropTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_INDEX", matches = ".+")
    void signsCurrentPublicIndexForLiveInterop() throws Exception {
        var index = Path.of(System.getenv("ANY2API_LIVE_GROK_INDEX"));
        var output = Path.of(System.getenv("ANY2API_LIVE_GROK_SIGNATURE"));
        var path = System.getenv("ANY2API_LIVE_GROK_PATH");
        var signer = new GrokWebStatsigSigner(new ObjectMapper(), new GrokWebProperties());

        var signature = signer.sign("POST", path, Files.readString(index));

        Files.writeString(output, signature);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_SSO", matches = ".+")
    void javaLifecycleHandlerCompletesCurrentChatAndQuotaProbe() {
        var mapper = new ObjectMapper();
        var platform = new Any2ApiProperties();
        platform.getAutomation().setBaseUrl(URI.create(
            System.getenv("ANY2API_LIVE_AUTOMATION_URL")));
        var transport = new BrowserTransportClient(WebClient.builder(), platform, mapper);
        var properties = new GrokWebProperties();
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class),
            new GrokWebStatsigSigner(mapper, properties), properties, mapper);
        var handler = new GrokWebLifecycleHandler(protocol);
        var credential = mapper.createObjectNode().put(
            "sso", System.getenv("ANY2API_LIVE_GROK_SSO"));
        var proxyJson = new String(Base64.getDecoder().decode(
            System.getenv("ANY2API_LIVE_PROXY_POOL_B64")));
        Map<String, Object> proxyPool = mapper.readValue(proxyJson, new TypeReference<>() {});

        var result = handler.execute(
            AutomationOperation.KEEPALIVE, credential, proxyPool).block();

        assertThat(result).isNotNull();
        assertThat(result.healthy())
            .as("lifecycle error class: %s", result.errorClass())
            .isTrue();
        assertThat(result.authExpired()).isFalse();
        assertThat(result.credentialPatch().path("browser_profile").asText())
            .isEqualTo("chrome136");
        assertThat(result.metadataPatch().path("available_modes").isArray()).isTrue();
        assertThat(result.metadataPatch().path("available_modes").size()).isGreaterThan(0);
        assertThat(result.metadataPatch().path("quota").isObject()).isTrue();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_SSO", matches = ".+")
    void javaToolProtocolCompletesCurrentForcedFunctionCall() {
        var mapper = new ObjectMapper();
        var platform = new Any2ApiProperties();
        platform.getAutomation().setBaseUrl(URI.create(
            System.getenv("ANY2API_LIVE_AUTOMATION_URL")));
        var transport = new BrowserTransportClient(WebClient.builder(), platform, mapper);
        var properties = new GrokWebProperties();
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class),
            new GrokWebStatsigSigner(mapper, properties), properties, mapper);
        var toolProtocol = new GrokWebToolProtocol(mapper);
        var requestMapper = new GrokWebRequestMapper(mapper, toolProtocol);
        var tool = mapper.createObjectNode().put("type", "function");
        tool.set("function", mapper.createObjectNode()
            .put("name", "lookup")
            .put("description", "Look up a numeric record by id")
            .set("parameters", mapper.createObjectNode().put("type", "object")
                .set("properties", mapper.createObjectNode()
                    .set("id", mapper.createObjectNode().put("type", "integer")))));
        var raw = mapper.createObjectNode();
        raw.set("tool_choice", mapper.createObjectNode().put("type", "function")
            .set("function", mapper.createObjectNode().put("name", "lookup")));
        var message = mapper.createObjectNode().put("role", "user")
            .put("content", "Call lookup for record id 42.");
        var request = new CanonicalRequest(
            "live-tool", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "grok_web", "grok-chat-fast", true, List.of(message),
            Map.of(), Map.of(), List.of(tool), Map.of(), raw);
        var prepared = requestMapper.prepare(request);
        var credential = mapper.createObjectNode().put(
            "sso", System.getenv("ANY2API_LIVE_GROK_SSO"));
        var proxyJson = new String(Base64.getDecoder().decode(
            System.getenv("ANY2API_LIVE_PROXY_POOL_B64")));
        Map<String, Object> proxyPool = mapper.readValue(proxyJson, new TypeReference<>() {});
        var decoder = new GrokWebEventDecoder(
            mapper, request.requestId(), "", prepared.toolSieve());

        var events = protocol.chat(
                credential, prepared.body(), "", proxyPool, "live-identity")
            .concatMapIterable(decoder::decode)
            .concatWith(reactor.core.publisher.Flux.defer(() ->
                reactor.core.publisher.Flux.fromIterable(decoder.finish())))
            .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.ToolCallStarted call
                && call.name().equals("lookup"))
            .anyMatch(event -> event instanceof CanonicalEvent.ToolCallCompleted call
                && mapper.readTree(call.arguments()).path("id").asInt() == 42)
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_SSO", matches = ".+")
    void javaGatewayChatCompletesCurrentTextResponse() {
        var mapper = new ObjectMapper();
        var platform = new Any2ApiProperties();
        platform.getAutomation().setBaseUrl(URI.create(
            System.getenv("ANY2API_LIVE_AUTOMATION_URL")));
        var transport = new BrowserTransportClient(WebClient.builder(), platform, mapper);
        var properties = new GrokWebProperties();
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class),
            new GrokWebStatsigSigner(mapper, properties), properties, mapper);
        var requestMapper = new GrokWebRequestMapper(
            mapper, new GrokWebToolProtocol(mapper));
        var message = mapper.createObjectNode().put("role", "user")
            .put("content", "Reply with exactly OK.");
        var request = new CanonicalRequest(
            "live-gateway", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "grok_web", "grok-chat-fast", true, List.of(message),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
        var prepared = requestMapper.prepare(request);
        var credential = mapper.createObjectNode().put(
            "sso", System.getenv("ANY2API_LIVE_GROK_SSO"));
        var proxyJson = new String(Base64.getDecoder().decode(
            System.getenv("ANY2API_LIVE_PROXY_POOL_B64")));
        Map<String, Object> proxyPool = mapper.readValue(
            proxyJson, new TypeReference<>() {});
        var decoder = new GrokWebEventDecoder(mapper, request.requestId());

        var events = protocol.chat(
                credential, prepared.body(), "", proxyPool, "live-identity")
            .concatMapIterable(decoder::decode)
            .concatWith(reactor.core.publisher.Flux.defer(() ->
                reactor.core.publisher.Flux.fromIterable(decoder.finish())))
            .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta text
                && !text.delta().isBlank())
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("stop"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_SSO", matches = ".+")
    void javaGatewayChatContinuesAnExistingConversation() {
        var mapper = new ObjectMapper();
        var platform = new Any2ApiProperties();
        platform.getAutomation().setBaseUrl(URI.create(
            System.getenv("ANY2API_LIVE_AUTOMATION_URL")));
        var transport = new BrowserTransportClient(WebClient.builder(), platform, mapper);
        var properties = new GrokWebProperties();
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class),
            new GrokWebStatsigSigner(mapper, properties), properties, mapper);
        var requestMapper = new GrokWebRequestMapper(
            mapper, new GrokWebToolProtocol(mapper));
        var credential = mapper.createObjectNode().put(
            "sso", System.getenv("ANY2API_LIVE_GROK_SSO"));
        var proxyJson = new String(Base64.getDecoder().decode(
            System.getenv("ANY2API_LIVE_PROXY_POOL_B64")));
        Map<String, Object> proxyPool = mapper.readValue(
            proxyJson, new TypeReference<>() {});

        var firstRequest = new CanonicalRequest(
            "live-continuation-first", CanonicalRequest.Protocol.RESPONSES,
            "grok_web", "grok-chat-fast", true,
            List.of(mapper.createObjectNode().put("role", "user")
                .put("content", "Reply with exactly FIRST.")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
        var firstBody = requestMapper.prepare(firstRequest).body();
        var firstDecoder = new GrokWebEventDecoder(mapper, firstRequest.requestId());
        var firstEvents = protocol.chat(
                credential, firstBody, "", proxyPool, "live-identity")
            .concatMapIterable(firstDecoder::decode)
            .concatWith(reactor.core.publisher.Flux.defer(() ->
                reactor.core.publisher.Flux.fromIterable(firstDecoder.finish())))
            .collectList().block();
        var state = firstDecoder.responseState().orElseThrow();

        var secondRequest = new CanonicalRequest(
            "live-continuation-second", CanonicalRequest.Protocol.RESPONSES,
            "grok_web", "grok-chat-fast", true,
            List.of(mapper.createObjectNode().put("role", "user")
                .put("content", "Reply with exactly SECOND.")),
            Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
        var secondBody = requestMapper.prepare(secondRequest).body();
        secondBody.put("responseId", state.path("upstream_response_id").asText());
        var conversationId = state.path("conversation_id").asText();
        var secondDecoder = new GrokWebEventDecoder(
            mapper, secondRequest.requestId(), conversationId);
        var secondEvents = protocol.chat(
                credential, secondBody, conversationId, proxyPool, "live-identity")
            .concatMapIterable(secondDecoder::decode)
            .concatWith(reactor.core.publisher.Flux.defer(() ->
                reactor.core.publisher.Flux.fromIterable(secondDecoder.finish())))
            .collectList().block();

        assertThat(firstEvents).isNotNull();
        assertThat(state.path("conversation_id").asText()).isNotBlank();
        assertThat(state.path("upstream_response_id").asText()).isNotBlank();
        assertThat(secondEvents).anyMatch(
            event -> event instanceof CanonicalEvent.OutputTextDelta text
                && text.delta().contains("SECOND"))
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("stop"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANY2API_LIVE_GROK_SSO", matches = ".+")
    void javaAccountTermsCompleteAcrossOneMultiOriginSession() {
        var mapper = new ObjectMapper();
        var platform = new Any2ApiProperties();
        platform.getAutomation().setBaseUrl(URI.create(
            System.getenv("ANY2API_LIVE_AUTOMATION_URL")));
        var transport = new BrowserTransportClient(WebClient.builder(), platform, mapper);
        var properties = new GrokWebProperties();
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class),
            new GrokWebStatsigSigner(mapper, properties), properties, mapper);
        var credential = mapper.createObjectNode().put(
            "sso", System.getenv("ANY2API_LIVE_GROK_SSO"));
        var proxyJson = new String(Base64.getDecoder().decode(
            System.getenv("ANY2API_LIVE_PROXY_POOL_B64")));
        Map<String, Object> proxyPool = mapper.readValue(proxyJson, new TypeReference<>() {});

        var result = protocol.applyAccountSettings(
            credential, proxyPool,
            new GrokWebProtocolClient.AccountSettings(true, null, false),
            "live-identity").block();

        assertThat(result).isNotNull();
        assertThat(result.metadataPatch().path("web_terms_version").asInt())
            .isEqualTo(properties.getTermsVersion());
    }
}
