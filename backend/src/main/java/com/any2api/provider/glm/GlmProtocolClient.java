package com.any2api.provider.glm;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.transport.BrowserTransportClient;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GlmProtocolClient {
    private final BrowserTransportClient transport;
    private final GlmCaptchaClient captcha;
    private final GlmRequestMapper requestMapper;
    private final GlmSigner signer;
    private final GlmProperties properties;
    private final ObjectMapper mapper;

    GlmProtocolClient(
        BrowserTransportClient transport,
        GlmCaptchaClient captcha,
        GlmRequestMapper requestMapper,
        GlmSigner signer,
        GlmProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.captcha = captcha;
        this.requestMapper = requestMapper;
        this.signer = signer;
        this.properties = properties;
        this.mapper = mapper;
    }

    Flux<byte[]> chat(
        GlmCredential credential,
        String email,
        CanonicalRequest request,
        Map<String, Object> proxyPool,
        String affinityKey,
        Consumer<JsonNode> credentialPatchSink
    ) {
        var timestamp = System.currentTimeMillis();
        var seed = requestMapper.prepareChat(request, timestamp);
        return Flux.usingWhen(
            transport.open(sessionCommand(credential, proxyPool, affinityKey)),
            session -> createChat(session, seed.body())
                .flatMapMany(chatId -> captcha.solve(
                        session.id(), Math.min(240, properties.getTimeoutSeconds()))
                    .flatMapMany(ticket -> streamCompletion(
                        session, credential, email, request, seed, chatId, ticket))),
            session -> close(session, credentialPatchSink),
            (session, ignored) -> close(session, credentialPatchSink),
            session -> close(session, credentialPatchSink));
    }

    private Mono<String> createChat(
        BrowserTransportClient.Session session,
        ObjectNode body
    ) {
        return transport.request(session.id(), new BrowserTransportClient.Request(
                "POST",
                "/api/v1/chats/new",
                Map.of("Content-Type", "application/json", "x-region", properties.getRegion()),
                BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
                body,
                properties.getTimeoutSeconds()))
            .flatMap(response -> {
                if (!response.successful()) {
                    return Mono.error(new GlmUpstreamException(
                        response.status(), summarize(response.status(), response.text())));
                }
                JsonNode value;
                try {
                    value = mapper.readTree(response.body());
                } catch (Exception error) {
                    return Mono.error(new GlmUpstreamException(
                        502, "GLM chats/new returned invalid JSON"));
                }
                var id = value.path("id").asText("").trim();
                return id.isBlank()
                    ? Mono.error(new GlmUpstreamException(
                        502, "GLM chats/new returned no chat id"))
                    : Mono.just(id);
            });
    }

    private Flux<byte[]> streamCompletion(
        BrowserTransportClient.Session session,
        GlmCredential credential,
        String email,
        CanonicalRequest request,
        GlmRequestMapper.ChatSeed seed,
        String chatId,
        String ticket
    ) {
        var timestamp = System.currentTimeMillis();
        var requestId = UUID.randomUUID().toString();
        var body = requestMapper.prepareCompletion(
            request, seed, chatId, ticket, email, timestamp);
        var signed = signer.sign(requestId, credential.userId(), seed.prompt(), timestamp);
        var path = completionPath(
            credential, chatId, requestId, seed.prompt(), signed.timestamp());
        var headers = Map.of(
            "Content-Type", "application/json",
            "x-fe-version", "prod-fe-" + properties.getFrontendVersion(),
            "x-region", properties.getRegion(),
            "x-signature", signed.signature());
        return transport.stream(session.id(), new BrowserTransportClient.Request(
            "POST",
            path,
            headers,
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body,
            properties.getTimeoutSeconds()));
    }

    private String completionPath(
        GlmCredential credential,
        String chatId,
        String requestId,
        String prompt,
        long timestamp
    ) {
        var profile = credential.deviceProfile();
        var width = positive(profile.path("screen_width").asInt(), 1440);
        var height = positive(profile.path("screen_height").asInt(), 900);
        var viewportWidth = positive(profile.path("viewport_width").asInt(), width);
        var viewportHeight = positive(profile.path("viewport_height").asInt(), height);
        var timezone = ZoneId.of("Asia/Tokyo");
        var now = ZonedDateTime.now(timezone);
        var base = URI.create(properties.getBaseUrl());
        var currentPath = "/c/" + chatId;
        var query = new LinkedHashMap<String, Object>();
        query.put("timestamp", timestamp);
        query.put("requestId", requestId);
        query.put("user_id", credential.userId());
        query.put("version", properties.getFrontendVersion());
        query.put("platform", "web");
        query.put("token", credential.token());
        query.put("user_agent", credential.userAgent());
        query.put("language", "en-US");
        query.put("languages", "en-US,en");
        query.put("timezone", timezone.getId());
        query.put("cookie_enabled", "true");
        query.put("screen_width", width);
        query.put("screen_height", height);
        query.put("screen_resolution", width + "x" + height);
        query.put("viewport_height", viewportHeight);
        query.put("viewport_width", viewportWidth);
        query.put("viewport_size", viewportWidth + "x" + viewportHeight);
        query.put("color_depth", 24);
        query.put("pixel_ratio", 1);
        query.put("current_url", properties.getBaseUrl() + currentPath);
        query.put("pathname", currentPath);
        query.put("search", "");
        query.put("hash", "");
        query.put("host", base.getHost());
        query.put("hostname", base.getHost());
        query.put("protocol", "https:");
        query.put("referrer", "");
        query.put("title", "Z.ai - Advanced AI Chatbot & Agent");
        query.put("timezone_offset", -now.getOffset().getTotalSeconds() / 60);
        query.put("local_time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        query.put("utc_time", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME));
        query.put("is_mobile", "false");
        query.put("is_touch", "false");
        query.put("max_touch_points", 0);
        query.put("browser_name", profile.path("browser_name").asText("chrome"));
        query.put("os_name", profile.path("os_name").asText("Windows"));
        query.put("signature_timestamp", timestamp);
        var builder = UriComponentsBuilder.fromPath("/api/v2/chat/completions");
        query.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        GlmCredential credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var origin = URI.create(properties.getBaseUrl());
        return new BrowserTransportClient.OpenCommand(
            origin,
            credential.cookies(),
            List.of("." + origin.getHost()),
            credential.userAgent(),
            credential.browserProfile(),
            "v2",
            proxyPool == null ? Map.of() : proxyPool,
            300,
            List.of(),
            affinityKey,
            !affinityKey.isBlank(),
            "");
    }

    private Mono<Void> close(
        BrowserTransportClient.Session session,
        Consumer<JsonNode> credentialPatchSink
    ) {
        return transport.close(session.id())
            .doOnNext(result -> credentialPatchSink.accept(result.contextPatch()))
            .then();
    }

    private int positive(int value, int fallback) { return value > 0 ? value : fallback; }

    private String summarize(int status, String body) {
        var compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) compact = compact.substring(0, 1000);
        return compact.isBlank() ? "GLM upstream returned HTTP " + status
            : "GLM upstream returned HTTP " + status + ": " + compact;
    }
}
