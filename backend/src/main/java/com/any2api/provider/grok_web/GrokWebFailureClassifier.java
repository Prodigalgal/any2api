package com.any2api.provider.grok_web;

import com.any2api.provider.ProviderFailure;
import com.any2api.transport.BrowserTransportClient;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class GrokWebFailureClassifier {
    ProviderFailure classify(Throwable error) {
        var message = error.getMessage() == null ? "" : error.getMessage();
        if (definitiveBlock(message)) {
            return new ProviderFailure("account_blocked", "Grok Web account is blocked",
                false, Map.of("channel", "web"));
        }
        if (rateLimited(message)) {
            return new ProviderFailure("rate_limited", "Grok Web model quota is exhausted",
                true, Map.of("channel", "web"));
        }
        if (error instanceof BrowserTransportClient.BrowserTransportException upstream) {
            var retryable = upstream.status() == 403 || upstream.status() == 429
                || upstream.status() >= 500;
            var antiBot = upstream.status() == 403
                && upstream.getMessage().matches("(?s).*\\\"code\\\"\\s*:\\s*7.*");
            var type = antiBot ? "anti_bot_rejected" : switch (upstream.status()) {
                case 401 -> "credential_rejected";
                case 403 -> "permission_or_egress_denied";
                case 429 -> "rate_limited";
                default -> "provider_upstream_error";
            };
            return new ProviderFailure(type, upstream.getMessage(), retryable,
                Map.of("status", upstream.status(), "channel", "web"));
        }
        if (error instanceof GrokWebEventDecoder.GrokWebStreamException stream) {
            if (rateLimited(stream.code() + " " + message)) {
                return new ProviderFailure("rate_limited", "Grok Web model quota is exhausted",
                    true, Map.of("code", stream.code(), "channel", "web"));
            }
            if ("7".equals(stream.code()) || message.toLowerCase().contains("anti-bot")) {
                return new ProviderFailure("anti_bot_rejected", stream.getMessage(), true,
                    Map.of("code", stream.code(), "channel", "web"));
            }
            return new ProviderFailure("provider_stream_error", stream.getMessage(), true,
                Map.of("code", stream.code(), "channel", "web"));
        }
        return new ProviderFailure("provider_transport_error",
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
            true, Map.of("channel", "web"));
    }

    private boolean definitiveBlock(String message) {
        var value = message.toLowerCase();
        if (value.contains("unauthorized:blocked-user") || value.contains("user is blocked")) {
            return true;
        }
        var permissionCode = value.contains("permission-denied")
            || value.contains("permission_denied");
        var explicitAccessDenial = value.contains("access to the chat endpoint is denied")
            || value.contains("update the permissions");
        var safety = value.contains("safety_check") || value.contains("usage guidelines");
        return permissionCode && explicitAccessDenial && !safety;
    }

    private boolean rateLimited(String message) {
        var value = message.toLowerCase();
        return value.contains("usage_limit_reached")
            || value.contains("usage limit")
            || value.contains("free-usage-exhausted")
            || value.contains("rate limit")
            || value.contains("rate_limit");
    }
}
