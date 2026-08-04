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
        var payload = body == null ? "" : body;
        return riskHeaders.generate(properties.getBaseUrl() + path, method, payload)
            .map(risk -> {
                var headers = new LinkedHashMap<String, String>();
                headers.put("Accept", "application/json, text/event-stream");
                headers.put("Content-Type", "application/json");
                headers.put("Origin", properties.getBaseUrl());
                headers.put("Referer", properties.getBaseUrl() + "/");
                headers.put("source", properties.getSource());
                headers.put("Timezone", ZonedDateTime.now(QWEN_TIMEZONE).format(WEB_TIMEZONE));
                headers.put("X-Request-Id", UUID.randomUUID().toString());
                headers.putAll(risk);
                return BrowserTransportClient.Request.binary(
                    method, path, Map.copyOf(headers),
                    BrowserTransportClient.FingerprintProfile.NONE,
                    payload.getBytes(StandardCharsets.UTF_8), timeout, null, "/");
            });
    }

    Mono<QwenRiskHeaderClient.BrowserResponse> browserFetch(
        String path,
        String body,
        QwenCredential credential,
        String refererPath,
        int timeout
    ) {
        return riskHeaders.browserFetch(
            "POST", path, body, credential.token(), refererPath, timeout);
    }
}
