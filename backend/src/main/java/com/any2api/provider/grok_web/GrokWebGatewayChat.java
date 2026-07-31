package com.any2api.provider.grok_web;

import com.any2api.transport.BrowserTransportClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class GrokWebGatewayChat {
    private static final int MAX_HANDSHAKE_FRAMES = 32;
    private static final int MAX_RESPONSE_FRAMES = 2048;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(210);

    private final BrowserTransportClient transport;
    private final GrokWebProperties properties;
    private final ObjectMapper mapper;

    GrokWebGatewayChat(
        BrowserTransportClient transport,
        GrokWebProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.properties = properties;
        this.mapper = mapper;
    }

    Flux<byte[]> stream(
        BrowserTransportClient.Session browserSession,
        ObjectNode request,
        String existingConversationId
    ) {
        return userId(browserSession).flatMapMany(userId -> Flux.usingWhen(
            transport.openWebSocket(
                browserSession.id(), gatewayPath(userId), properties.getBaseUrl(), 60,
                BrowserTransportClient.WebSocketMode.BROWSER),
            websocket -> attach(
                    browserSession, websocket, request, existingConversationId)
                .flatMapMany(attached -> sendPrompt(
                        browserSession, websocket, attached, request)
                    .thenMany(Flux.concat(
                        Flux.just(attached.frame()),
                        responseFrames(browserSession, websocket)))),
            websocket -> transport.closeWebSocket(browserSession.id(), websocket.id()),
            (websocket, ignored) -> transport.closeWebSocket(
                browserSession.id(), websocket.id()),
            websocket -> transport.closeWebSocket(browserSession.id(), websocket.id())));
    }

    Mono<Void> probe(
        BrowserTransportClient.Session browserSession,
        ObjectNode request
    ) {
        return userId(browserSession).flatMap(userId -> Mono.usingWhen(
            transport.openWebSocket(
                browserSession.id(), gatewayPath(userId), properties.getBaseUrl(), 60,
                BrowserTransportClient.WebSocketMode.BROWSER),
            websocket -> attach(browserSession, websocket, request, "").then(),
            websocket -> transport.closeWebSocket(browserSession.id(), websocket.id()),
            (websocket, ignored) -> transport.closeWebSocket(
                browserSession.id(), websocket.id()),
            websocket -> transport.closeWebSocket(browserSession.id(), websocket.id())));
    }

    private Mono<String> userId(BrowserTransportClient.Session session) {
        return transport.request(session.id(), new BrowserTransportClient.Request(
                "GET", "/api/auth/session", Map.of(),
                BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH, null, 30))
            .map(response -> {
                if (!response.successful()) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        response.status(), "Grok Web session returned HTTP " + response.status());
                }
                var root = mapper.readTree(response.body());
                var value = root.path("session").path("userId").asText("").trim();
                if (value.isBlank()) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        401, "Grok Web session is not authenticated");
                }
                return value;
            });
    }

    private Mono<Attached> attach(
        BrowserTransportClient.Session browserSession,
        BrowserTransportClient.WebSocketHandle websocket,
        ObjectNode request,
        String existingConversationId
    ) {
        return transport.sendWebSocket(
                browserSession.id(), websocket.id(), sessionCreate(request, existingConversationId))
            .thenMany(Flux.range(0, MAX_HANDSHAKE_FRAMES)
                .concatMap(ignored -> transport.receiveWebSocket(
                    browserSession.id(), websocket.id()))
                .map(BrowserTransportClient.WebSocketFrame::body)
                .doOnNext(this::requireNoGatewayError)
                .filter(this::isConversationAttached)
                .map(this::attached))
            .next()
            .switchIfEmpty(Mono.error(new BrowserTransportClient.BrowserTransportException(
                502, "Grok Web gateway omitted conversation.attached")))
            .timeout(Duration.ofSeconds(60));
    }

    private Mono<Void> sendPrompt(
        BrowserTransportClient.Session browserSession,
        BrowserTransportClient.WebSocketHandle websocket,
        Attached attached,
        ObjectNode request
    ) {
        var now = System.currentTimeMillis();
        var item = mapper.createObjectNode()
            .put("type", "message")
            .put("role", "user");
        var xGrok = mapper.createObjectNode()
            .put("client_message_id", UUID.randomUUID().toString());
        xGrok.putArray("input_chunks").addObject().putObject("text")
            .put("text", request.path("message").asText(""));
        item.set("x_grok", xGrok);

        var itemEvent = event("conversation.item.create", "evt_msg_" + now)
            .set("item", item);
        var parentResponseId = request.path("responseId").asText("").trim();
        if (!parentResponseId.isBlank()) itemEvent.put("parent_response_id", parentResponseId);

        var createItem = mapper.createObjectNode()
            .put("session_id", attached.sessionId())
            .set("event", itemEvent);
        var createResponse = mapper.createObjectNode()
            .put("session_id", attached.sessionId())
            .set("event", event("response.create", "evt_resp_" + now));
        return transport.sendWebSocket(browserSession.id(), websocket.id(), createItem)
            .then(transport.sendWebSocket(browserSession.id(), websocket.id(), createResponse));
    }

    private Flux<byte[]> responseFrames(
        BrowserTransportClient.Session session,
        BrowserTransportClient.WebSocketHandle websocket
    ) {
        return Flux.range(0, MAX_RESPONSE_FRAMES)
            .concatMap(ignored -> transport.receiveWebSocket(session.id(), websocket.id()))
            .map(BrowserTransportClient.WebSocketFrame::body)
            .doOnNext(this::requireNoGatewayError)
            .takeUntil(this::isResponseDone)
            .timeout(RESPONSE_TIMEOUT);
    }

    private ObjectNode sessionCreate(ObjectNode request, String existingConversationId) {
        var xGrok = mapper.createObjectNode();
        xGrok.putArray("protocol_capabilities")
            .add("conversation_attached")
            .add("custom_methods_v1");
        xGrok.put("use_chunk", true);
        xGrok.put("enable_side_by_side", request.path("enableSideBySide").asBoolean(true));
        xGrok.put("force_side_by_side", request.path("forceSideBySide").asBoolean(false));
        xGrok.put("enable_image_generation",
            request.path("enableImageGeneration").asBoolean(false));
        xGrok.put("image_generation_count",
            request.path("imageGenerationCount").asInt(2));
        xGrok.put("disable_text_follow_ups",
            request.path("disableTextFollowUps").asBoolean(false));
        xGrok.put("disable_artifact", true);
        xGrok.put("force_concise", request.path("forceConcise").asBoolean(false));
        if (request.path("disableMemory").asBoolean(false)) xGrok.put("disable_memory", true);
        if (request.path("temporary").asBoolean(false)) {
            xGrok.put("keep_context", false);
            xGrok.put("is_temporary", true);
        }
        var conversationId = existingConversationId == null ? "" : existingConversationId.trim();
        if (!conversationId.isBlank()) {
            xGrok.put("conversation_id", conversationId);
            xGrok.put("load_existing", true);
            xGrok.put("needs_history", false);
        }
        var session = mapper.createObjectNode()
            .put("model", request.path("modeId").asText("fast"))
            .set("x_grok", xGrok);
        return mapper.createObjectNode().set("event",
            event("session.create", eventId("session")).set("session", session));
    }

    private ObjectNode event(String type, String eventId) {
        return mapper.createObjectNode().put("type", type).put("event_id", eventId);
    }

    private String eventId(String purpose) {
        return "evt_" + purpose + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String gatewayPath(String userId) {
        return "/ws/mgw/?uid=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
    }

    private Attached attached(byte[] frame) {
        var root = mapper.readTree(frame);
        var sessionId = root.path("session_id").asText("").trim();
        var conversationId = root.path("event").path("conversation").path("id")
            .asText("").trim();
        if (sessionId.isBlank() || conversationId.isBlank()) {
            throw new BrowserTransportClient.BrowserTransportException(
                502, "Grok Web gateway returned incomplete conversation.attached");
        }
        return new Attached(sessionId, conversationId, frame);
    }

    private boolean isConversationAttached(byte[] frame) {
        return "conversation.attached".equals(eventType(frame));
    }

    private boolean isResponseDone(byte[] frame) {
        return "response.done".equals(eventType(frame));
    }

    private String eventType(byte[] frame) {
        try {
            return mapper.readTree(frame).path("event").path("type").asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void requireNoGatewayError(byte[] frame) {
        JsonNode event;
        try {
            event = mapper.readTree(frame).path("event");
        } catch (RuntimeException error) {
            throw new BrowserTransportClient.BrowserTransportException(
                502, "Grok Web gateway returned malformed JSON");
        }
        if (!"error".equals(event.path("type").asText(""))) return;
        var detail = event.path("error");
        throw new GrokWebEventDecoder.GrokWebStreamException(
            detail.path("code").asText("gateway_error"),
            detail.path("message").asText("Grok Web gateway failed"));
    }

    private record Attached(String sessionId, String conversationId, byte[] frame) {
        private Attached { frame = frame.clone(); }
        @Override public byte[] frame() { return frame.clone(); }
    }
}
