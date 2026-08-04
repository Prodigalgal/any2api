package com.any2api.provider.qwen;

import com.any2api.transport.BrowserTransportClient;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class QwenTransportRequests {
    private static final DateTimeFormatter WEB_TIMEZONE = DateTimeFormatter.ofPattern(
        "EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.ENGLISH);
    private static final ZoneId QWEN_TIMEZONE = ZoneId.of("Asia/Shanghai");
    private final QwenProperties properties;
    private final QwenRiskHeaderClient riskHeaders;

    QwenTransportRequests(QwenProperties properties, QwenRiskHeaderClient riskHeaders) {
        this.properties = properties;
        this.riskHeaders = riskHeaders;
    }

    Mono<BrowserTransportClient.Request> create(
        String method,
        String path,
        String body,
        int timeout
    ) {
        return create(method, path, body, timeout, WebSurface.DEFAULT, "");
    }

    Mono<BrowserTransportClient.Request> createNewChat(
        String path,
        String body,
        int timeout
    ) {
        return create("POST", path, body, timeout, WebSurface.NEW_CHAT, "");
    }

    Mono<BrowserTransportClient.Request> createCompletion(
        String path,
        String body,
        int timeout,
        String chatId
    ) {
        return create("POST", path, body, timeout, WebSurface.COMPLETION, chatId);
    }

    private Mono<BrowserTransportClient.Request> create(
        String method,
        String path,
        String body,
        int timeout,
        WebSurface surface,
        String chatId
    ) {
        var payload = body == null ? "" : body;
        return riskHeaders.generate(properties.getBaseUrl() + path, method, payload)
            .map(risk -> {
                var headers = new LinkedHashMap<String, String>();
                headers.put("Accept", surface.accept());
                headers.put("Content-Type", "application/json");
                headers.put("Origin", properties.getBaseUrl());
                headers.put("Referer", properties.getBaseUrl() + surface.referer(chatId));
                headers.put("source", properties.getSource());
                headers.put("Timezone", ZonedDateTime.now(QWEN_TIMEZONE).format(WEB_TIMEZONE));
                headers.put("X-Request-Id", UUID.randomUUID().toString());
                if (surface == WebSurface.COMPLETION) {
                    headers.put("X-Accel-Buffering", "no");
                }
                headers.putAll(risk);
                return BrowserTransportClient.Request.binary(
                    method, path, Map.copyOf(headers),
                    BrowserTransportClient.FingerprintProfile.NONE,
                    payload.getBytes(StandardCharsets.UTF_8), timeout, null, "/");
            });
    }

    private enum WebSurface {
        DEFAULT("application/json, text/event-stream", "/"),
        NEW_CHAT("application/json, text/plain, */*", "/c/new-chat"),
        COMPLETION("application/json", "/c/");

        private final String accept;
        private final String referer;

        WebSurface(String accept, String referer) {
            this.accept = accept;
            this.referer = referer;
        }

        String accept() { return accept; }

        String referer(String chatId) {
            return this == COMPLETION ? referer + chatId : referer;
        }
    }
}
