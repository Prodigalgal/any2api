package com.any2api.provider.grok_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.transport.BrowserClearanceCoordinator;
import com.any2api.transport.BrowserTransportClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GrokWebProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestUsesTierSpecificModeAndProviderOwnedPayload() {
        var raw = mapper.createObjectNode().put("model", "grok_web/grok-chat-auto");
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("id", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "grok_web", "grok-chat-auto", true, List.of(message), Map.of(), Map.of(),
            List.of(), Map.of(), raw);

        var payload = requestMapper().prepare(request).body();

        assertThat(payload.path("modeId").asText()).isEqualTo("auto");
        assertThat(payload.path("message").asText()).contains("[user]\nhello");
        assertThat(payload.path("temporary").asBoolean()).isTrue();
    }

    @Test
    void streamingJsonObjectsSurviveArbitraryChunkBoundaries() {
        var decoder = new GrokWebEventDecoder(mapper, "request-id");
        var data = ("noise{\"result\":{\"conversation\":{\"conversationId\":\"c1\"}}}"
            + "{\"result\":{\"response\":{\"token\":\"why\",\"isThinking\":true}}}"
            + "{\"result\":{\"response\":{\"token\":\"answer\",\"messageTag\":\"final\","
            + "\"modelResponse\":{\"responseId\":\"upstream-r1\"}}}}")
            .getBytes(StandardCharsets.UTF_8);

        var events = new java.util.ArrayList<CanonicalEvent>();
        for (var index = 0; index < data.length; index += 7) {
            events.addAll(decoder.decode(java.util.Arrays.copyOfRange(
                data, index, Math.min(data.length, index + 7))));
        }
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(CanonicalEvent.ResponseStarted.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.ReasoningDelta value
                && value.delta().equals("why"))
            .anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta value
                && value.delta().equals("answer"))
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
        assertThat(decoder.responseState()).hasValueSatisfying(state -> {
            assertThat(state.path("conversation_id").asText()).isEqualTo("c1");
            assertThat(state.path("upstream_response_id").asText()).isEqualTo("upstream-r1");
        });
    }

    @Test
    void streamingJsonPreservesUtf8CharactersSplitAcrossChunks() {
        var decoder = new GrokWebEventDecoder(mapper, "request-id");
        var data = "{\"result\":{\"response\":{\"token\":\"你好\"}}}"
            .getBytes(StandardCharsets.UTF_8);
        var events = new java.util.ArrayList<CanonicalEvent>();

        for (var value : data) events.addAll(decoder.decode(new byte[] {value}));

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta value
            && value.delta().equals("你好"));
    }

    @Test
    void continuationShapeUpdatesParentResponseState() {
        var decoder = new GrokWebEventDecoder(mapper, "next-request", "conversation-1");
        var data = ("{\"result\":{\"token\":\"continued\"}}"
            + "{\"result\":{\"modelResponse\":{\"responseId\":\"response-2\","
            + "\"parentResponseId\":\"response-1\"}}}")
            .getBytes(StandardCharsets.UTF_8);

        var events = decoder.decode(data);

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta text
            && text.delta().equals("continued"));
        assertThat(decoder.responseState()).hasValueSatisfying(state -> {
            assertThat(state.path("conversation_id").asText()).isEqualTo("conversation-1");
            assertThat(state.path("upstream_response_id").asText()).isEqualTo("response-2");
        });
    }

    @Test
    void gatewayEventsProduceCanonicalTextAndResponseState() {
        var decoder = new GrokWebEventDecoder(mapper, "gateway-request");
        var events = new java.util.ArrayList<CanonicalEvent>();

        events.addAll(decoder.decode(gatewayEvent("""
            {"type":"conversation.attached","conversation":{"id":"conversation-1"}}
            """)));
        events.addAll(decoder.decode(gatewayEvent("""
            {"type":"response.created","response":{"id":"response-1"}}
            """)));
        events.addAll(decoder.decode(gatewayEvent("""
            {"type":"response.chunk","chunk":{"text":{
              "channel":"CHANNEL_ASSISTANT_RESPONSE","text":"你好"
            }}}
            """)));
        events.addAll(decoder.decode(gatewayEvent("""
            {"type":"response.done","response":{"id":"response-1","status":"completed"}}
            """)));
        events.addAll(decoder.finish());

        assertThat(events).anyMatch(CanonicalEvent.ResponseStarted.class::isInstance)
            .anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta text
                && text.delta().equals("你好"))
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
        assertThat(decoder.responseState()).hasValueSatisfying(state -> {
            assertThat(state.path("conversation_id").asText()).isEqualTo("conversation-1");
            assertThat(state.path("upstream_response_id").asText()).isEqualTo("response-1");
        });
    }

    @Test
    void tierEligibilityIsProviderLocal() {
        var model = GrokWebModelCatalog.require("grok-chat-auto");
        assertThat(GrokWebModelCatalog.supports("basic", model)).isFalse();
        assertThat(GrokWebModelCatalog.supports("super", model)).isTrue();
        assertThat(new ProviderAccountProfile(UUID.randomUUID(), Map.of("tier", "super")).metadata())
            .containsEntry("tier", "super");
    }

    @Test
    void statsigMetadataAcceptsCurrentUnicodeDash() {
        assertThat(GrokWebStatsigSigner.extractMeta(
            "<meta name=\"grok-site\u2015verification\" content=\"live-value\">"))
            .isEqualTo("live-value");
    }

    @Test
    void statsigSignerMatchesSourceDerivedCrossLanguageVector() {
        var verification = new byte[48];
        for (var index = 0; index < verification.length; index++) {
            verification[index] = (byte) index;
        }
        var groups = mapper.createArrayNode();
        for (var groupIndex = 0; groupIndex < 4; groupIndex++) {
            var group = groups.addArray();
            for (var curveIndex = 0; curveIndex < 16; curveIndex++) {
                var curve = group.addObject().put("deg", 120 + curveIndex);
                curve.putArray("color").add(10 + groupIndex).add(20).add(30)
                    .add(210).add(220).add(230);
                curve.putArray("bezier").add(80).add(100).add(160).add(200);
            }
        }
        var curves = mapper.writeValueAsString(groups).replace("\"", "\\\"");
        var html = "<meta name=\"grok-site-verification\" content=\""
            + Base64.getEncoder().encodeToString(verification) + "\">"
            + "\\\"curves\\\":" + curves
            + ",\\\"css_class\\\":\\\"r-ogi2o\\\"";
        var signer = new GrokWebStatsigSigner(mapper, new GrokWebProperties());

        var signature = signer.sign(
            "POST", "/rest/app-chat/conversations/new", signer.parse(html), 123456, 37);

        assertThat(signature).isEqualTo(
            "JSUkJyYhICMiLSwvLikoKyo1NDc2MTAzMj08Pz45ODs6BQQHBgEAAwINDA8OCQgLCmXH"
                + "JCV1hLcfDtt06UKgboUW0R8EJg");
        assertThat(Base64.getDecoder().decode(signature)).hasSize(70);
    }

    @Test
    void statsigEnvironmentDoesNotDependOnAFixedCssClass() {
        var verification = Base64.getEncoder().encodeToString(new byte[48]);
        var curve = "{\"color\":[1,2,3,4,5,6],\"deg\":120,"
            + "\"bezier\":[10,20,30,40]}";
        var group = "[" + String.join(",", java.util.Collections.nCopies(16, curve)) + "]";
        var curves = "[" + String.join(",", java.util.Collections.nCopies(4, group)) + "]";
        var html = "<meta name=\"grok-site-verification\" content=\"" + verification
            + "\">\\\"curves\\\":" + curves.replace("\"", "\\\"")
            + ",\\\"css_class\\\":\\\"r-runtime-value\\\"";

        var environment = new GrokWebStatsigSigner(mapper, new GrokWebProperties()).parse(html);

        assertThat(environment.curves()).hasSize(4);
    }

    @Test
    void toolsSupportChatAndResponsesShapesWithForcedChoice() {
        var chatTool = mapper.createObjectNode().put("type", "function");
        chatTool.set("function", mapper.createObjectNode()
            .put("name", "get_weather")
            .put("description", "Get weather")
            .set("parameters", mapper.createObjectNode().put("type", "object")));
        var chatRaw = mapper.createObjectNode().put("model", "grok_web/grok-chat-fast");
        chatRaw.set("tool_choice", mapper.createObjectNode().put("type", "function")
            .set("function", mapper.createObjectNode().put("name", "get_weather")));
        var chat = request(CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            chatRaw, List.of(chatTool), List.of(message("user", "weather")));

        var chatPrepared = requestMapper().prepare(chat);

        assertThat(chatPrepared.body().path("message").asText())
            .contains("Tool: get_weather")
            .contains("You MUST call the tool named \"get_weather\"")
            .contains("<tool_calls>");

        var responseTool = mapper.createObjectNode()
            .put("type", "function")
            .put("name", "lookup")
            .set("parameters", mapper.createObjectNode().put("type", "object"));
        var responseRaw = mapper.createObjectNode().put("tool_choice", "required");
        var response = request(CanonicalRequest.Protocol.RESPONSES,
            responseRaw, List.of(responseTool), List.of(message("user", "lookup")));

        assertThat(requestMapper().prepare(response).body().path("message").asText())
            .contains("Tool: lookup")
            .contains("MUST call at least one available tool");
    }

    @Test
    void responseToolHistoryIsRenderedIntoContinuationPrompt() {
        var call = mapper.createObjectNode().put("type", "function_call")
            .put("name", "lookup").put("arguments", "{\"id\":1}");
        var output = mapper.createObjectNode().put("type", "function_call_output")
            .put("call_id", "call_1").put("output", "found");
        var request = request(CanonicalRequest.Protocol.RESPONSES,
            mapper.createObjectNode(), List.of(), List.of(call, output));

        var prompt = requestMapper().prepare(request).body().path("message").asText();

        assertThat(prompt).contains("<tool_name>lookup</tool_name>")
            .contains("<parameters>{\"id\":1}</parameters>")
            .contains("[tool result for call_1]\nfound");
    }

    @Test
    void toolStreamSieveHandlesArbitraryBoundariesAndParallelCalls() {
        var tool = mapper.createObjectNode().put("type", "function")
            .put("name", "lookup")
            .set("parameters", mapper.createObjectNode().put("type", "object"));
        var request = request(CanonicalRequest.Protocol.RESPONSES,
            mapper.createObjectNode(), List.of(tool), List.of(message("user", "lookup")));
        var tools = new GrokWebToolProtocol(mapper);
        var decoder = new GrokWebEventDecoder(
            mapper, "tool-request", "", tools.sieve(tools.parse(request)));
        var first = upstreamToken("prefix <tool_ca");
        var second = upstreamToken("lls><tool_call><tool_name>lookup</tool_name>"
            + "<parameters>{\"id\":1}</parameters></tool_call>"
            + "<tool_call><tool_name>lookup</tool_name>"
            + "<parameters>{\"id\":2}</parameters></tool_call></tool_calls>");

        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode(first));
        events.addAll(decoder.decode(second));
        events.addAll(decoder.finish());

        assertThat(events.stream().filter(CanonicalEvent.ToolCallStarted.class::isInstance))
            .hasSize(2);
        assertThat(events.stream().filter(CanonicalEvent.ToolCallCompleted.class::isInstance))
            .hasSize(2);
        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.OutputTextDelta text
                && text.delta().equals("prefix "))
            .noneMatch(event -> event instanceof CanonicalEvent.OutputTextDelta text
                && text.delta().contains("<tool_"))
            .anyMatch(event -> event instanceof CanonicalEvent.Completed completed
                && completed.finishReason().equals("tool_calls"));
    }

    @Test
    void undeclaredAndInvalidToolCallsNeverBecomeCanonicalEvents() {
        var tool = mapper.createObjectNode().put("type", "function")
            .put("name", "allowed")
            .set("parameters", mapper.createObjectNode().put("type", "object"));
        var request = request(CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            mapper.createObjectNode(), List.of(tool), List.of(message("user", "test")));
        var tools = new GrokWebToolProtocol(mapper);
        var decoder = new GrokWebEventDecoder(
            mapper, "tool-request", "", tools.sieve(tools.parse(request)));

        var events = decoder.decode(upstreamToken(
            "<tool_calls><tool_call><tool_name>undeclared</tool_name>"
                + "<parameters>[]</parameters></tool_call></tool_calls>"));

        assertThat(events).noneMatch(CanonicalEvent.ToolCallStarted.class::isInstance);
    }

    @Test
    void failureClassifierSeparatesBlockedRateLimitedAndAntiBotCodeSeven() {
        var classifier = new GrokWebFailureClassifier();

        assertThat(classifier.classify(new BrowserTransportClient.BrowserTransportException(
            403, "{\"error\":{\"code\":7,\"message\":\"User is blocked "
                + "[WKE=unauthorized:blocked-user]\"}}" )).type())
            .isEqualTo("account_blocked");
        assertThat(classifier.classify(new GrokWebEventDecoder.GrokWebStreamException(
            "subscription:free-usage-exhausted", "Usage limit reached")).type())
            .isEqualTo("rate_limited");
        assertThat(classifier.classify(new GrokWebEventDecoder.GrokWebStreamException(
            "7", "Something went wrong")).type())
            .isEqualTo("anti_bot_rejected");
        assertThat(classifier.classify(new BrowserTransportClient.BrowserTransportException(
            403, "permission-denied: request rejected by policy")).type())
            .isEqualTo("permission_or_egress_denied");
        assertThat(classifier.classify(new BrowserTransportClient.BrowserTransportException(
            403, "permission-denied: Content violates usage guidelines. "
                + "SAFETY_CHECK_TYPE_VIOLENCE")).type())
            .isEqualTo("permission_or_egress_denied");
    }

    @Test
    void cloudflareClassifierDoesNotConfuseProviderPermissionDenials() {
        assertThat(GrokWebProtocolClient.isCloudflareChallenge(
            new BrowserTransportClient.BrowserTransportException(
                403, "Grok Web index returned a Cloudflare challenge"))).isTrue();
        assertThat(GrokWebProtocolClient.isCloudflareChallenge(
            new BrowserTransportClient.BrowserTransportException(
                403, "permission-denied: account policy"))).isFalse();
        assertThat(GrokWebProtocolClient.isCloudflareChallenge(
            new BrowserTransportClient.BrowserTransportException(
                401, "Cloudflare challenge"))).isFalse();
    }

    @Test
    void chatUsesCurrentGatewayHandshakeAndContinuationFields() {
        var transport = mock(BrowserTransportClient.class);
        var clearance = mock(BrowserClearanceCoordinator.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var session = new BrowserTransportClient.Session(
            "session", "ua", "chrome136", "a".repeat(64));
        when(transport.open(any())).thenReturn(Mono.just(session));
        when(transport.request(anyString(), any())).thenReturn(Mono.just(
            new BrowserTransportClient.BufferedResponse(200, "application/json",
                "{\"status\":\"authenticated\",\"session\":{\"userId\":\"user-id\"}}"
                    .getBytes(StandardCharsets.UTF_8))));
        when(transport.openWebSocket(anyString(), anyString(), any(), anyInt(), any()))
            .thenReturn(Mono.just(new BrowserTransportClient.WebSocketHandle("socket")));
        when(transport.sendWebSocket(anyString(), anyString(), any()))
            .thenReturn(Mono.empty());
        when(transport.receiveWebSocket("session", "socket")).thenReturn(
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"session.created"}}
                """)),
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"conversation.attached",
                  "conversation":{"id":"conversation-1"}}}
                """)),
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"response.created",
                  "response":{"id":"response-1"}}}
                """)),
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"response.chunk",
                  "chunk":{"text":{"channel":"CHANNEL_ASSISTANT_RESPONSE","text":"ok"}}}}
                """)),
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"response.done",
                  "response":{"id":"response-1","status":"completed"}}}
                """)));
        when(transport.closeWebSocket("session", "socket")).thenReturn(Mono.empty());
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(mapper.createObjectNode())));
        var protocol = new GrokWebProtocolClient(
            transport, clearance, signer, new GrokWebProperties(), mapper);

        var body = mapper.createObjectNode()
            .put("message", "hello")
            .put("modeId", "fast")
            .put("responseId", "parent-response-id");
        var result = protocol.chat(
                mapper.createObjectNode().put("sso", "secret"),
                body, "conversation-0", Map.of(), "identity")
            .collectList().block();

        assertThat(result).hasSize(4);
        var path = ArgumentCaptor.forClass(String.class);
        verify(transport).openWebSocket(
            anyString(), path.capture(), any(), anyInt(), any());
        assertThat(path.getValue()).isEqualTo("/ws/mgw/?uid=user-id");
        var sent = ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(transport, times(3)).sendWebSocket(anyString(), anyString(), sent.capture());
        assertThat(sent.getAllValues()).extracting(value ->
                value.path("event").path("type").asText())
            .containsExactly(
                "session.create", "conversation.item.create", "response.create");
        assertThat(sent.getAllValues().get(0).path("event").path("session")
            .path("x_grok").path("protocol_capabilities"))
            .extracting(JsonNode::asText)
            .containsExactly("conversation_attached", "custom_methods_v1");
        var sessionOptions = sent.getAllValues().get(0).path("event")
            .path("session").path("x_grok");
        assertThat(sessionOptions.path("conversation_id").asText())
            .isEqualTo("conversation-0");
        assertThat(sessionOptions.path("load_existing").asBoolean()).isTrue();
        assertThat(sessionOptions.path("needs_history").asBoolean()).isFalse();
        assertThat(sent.getAllValues().get(1).path("event")
            .path("parent_response_id").asText()).isEqualTo("parent-response-id");
        assertThat(sent.getAllValues().get(2).path("event")
            .has("parent_response_id")).isFalse();
        verify(transport, times(1)).open(any());
    }

    @Test
    void gatewayProbeStopsAfterTheAuthenticatedConversationHandshake() {
        var transport = mock(BrowserTransportClient.class);
        var session = new BrowserTransportClient.Session(
            "session", "ua", "chrome136", "a".repeat(64));
        when(transport.request(anyString(), any())).thenReturn(Mono.just(
            new BrowserTransportClient.BufferedResponse(200, "application/json",
                "{\"session\":{\"userId\":\"user-id\"}}"
                    .getBytes(StandardCharsets.UTF_8))));
        when(transport.openWebSocket(anyString(), anyString(), any(), anyInt(), any()))
            .thenReturn(Mono.just(new BrowserTransportClient.WebSocketHandle("socket")));
        when(transport.sendWebSocket(anyString(), anyString(), any()))
            .thenReturn(Mono.empty());
        when(transport.receiveWebSocket("session", "socket")).thenReturn(
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"session.created"}}
                """)),
            Mono.just(websocketFrame("""
                {"session_id":"gateway-session","event":{"type":"conversation.attached",
                  "conversation":{"id":"conversation-1"}}}
                """)));
        when(transport.closeWebSocket("session", "socket")).thenReturn(Mono.empty());
        var gateway = new GrokWebGatewayChat(
            transport, new GrokWebProperties(), mapper);

        gateway.probe(session, mapper.createObjectNode().put("modeId", "fast")).block();

        verify(transport, times(1)).sendWebSocket(anyString(), anyString(), any());
        verify(transport, times(2)).receiveWebSocket("session", "socket");
        verify(transport).closeWebSocket("session", "socket");
    }

    private GrokWebRequestMapper requestMapper() {
        return new GrokWebRequestMapper(mapper, new GrokWebToolProtocol(mapper));
    }

    private CanonicalRequest request(
        CanonicalRequest.Protocol protocol,
        tools.jackson.databind.node.ObjectNode raw,
        List<tools.jackson.databind.JsonNode> tools,
        List<tools.jackson.databind.JsonNode> messages
    ) {
        return new CanonicalRequest("id", protocol, "grok_web", "grok-chat-fast", true,
            messages, Map.of(), Map.of(), tools, Map.of(), raw);
    }

    private tools.jackson.databind.node.ObjectNode message(String role, String content) {
        return mapper.createObjectNode().put("role", role).put("content", content);
    }

    private byte[] upstreamToken(String token) {
        var root = mapper.createObjectNode();
        root.set("result", mapper.createObjectNode()
            .set("response", mapper.createObjectNode().put("token", token)));
        return mapper.writeValueAsBytes(root);
    }

    private byte[] gatewayEvent(String event) {
        return mapper.writeValueAsBytes(mapper.createObjectNode().set(
            "event", mapper.readTree(event)));
    }

    private BrowserTransportClient.WebSocketFrame websocketFrame(String value) {
        return new BrowserTransportClient.WebSocketFrame(
            value.getBytes(StandardCharsets.UTF_8), 1);
    }
}
