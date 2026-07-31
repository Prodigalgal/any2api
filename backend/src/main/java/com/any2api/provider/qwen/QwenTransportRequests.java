package com.any2api.provider.qwen;

import com.any2api.transport.BrowserTransportClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class QwenTransportRequests {
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
                headers.put("Timezone", java.time.ZonedDateTime.now().toString());
                headers.put("X-Request-Id", UUID.randomUUID().toString());
                headers.putAll(risk);
                return BrowserTransportClient.Request.binary(
                    method, path, Map.copyOf(headers),
                    BrowserTransportClient.FingerprintProfile.NONE,
                    payload.getBytes(StandardCharsets.UTF_8), timeout, null, "/");
            });
    }
}
