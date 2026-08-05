package com.any2api.provider.qwen;

import com.any2api.account.LeasedProviderAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

record QwenCredential(
    String token,
    String userId,
    String userAgent,
    String browserProfile,
    Map<String, String> cookies,
    JsonNode browserState,
    JsonNode browserFingerprint
) {
    QwenCredential {
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
        browserState = browserState == null || !browserState.isObject()
            ? JsonNodeFactory.instance.objectNode() : browserState.deepCopy();
        browserFingerprint = browserFingerprint == null || !browserFingerprint.isObject()
            ? JsonNodeFactory.instance.objectNode() : browserFingerprint.deepCopy();
    }

    QwenCredential(String token, String userId) {
        this(token, userId, "", "chrome146", Map.of(),
            JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode());
    }

    QwenCredential(
        String token,
        String userId,
        String userAgent,
        String browserProfile,
        Map<String, String> cookies,
        JsonNode browserState
    ) {
        this(token, userId, userAgent, browserProfile, cookies, browserState,
            JsonNodeFactory.instance.objectNode());
    }

    @Override public JsonNode browserState() { return browserState.deepCopy(); }
    @Override public JsonNode browserFingerprint() { return browserFingerprint.deepCopy(); }

    static QwenCredential from(LeasedProviderAccount account) {
        var value = account.credential();
        for (var field : new String[] {"token", "access_token", "jwt"}) {
            var token = value.path(field).asText("").trim();
            if (!token.isBlank()) {
                return new QwenCredential(
                    token,
                    value.path("user_id").asText("").trim(),
                    value.path("user_agent").asText("").trim(),
                    value.path("browser_profile").asText("chrome146").trim(),
                    cookies(value), value.path("browser_state"),
                    value.path("browser_fingerprint"));
            }
        }
        throw new IllegalStateException("Qwen account credential requires token");
    }

    private static Map<String, String> cookies(tools.jackson.databind.JsonNode credential) {
        var output = new LinkedHashMap<String, String>();
        credential.path("cookies").properties().forEach(entry -> {
            if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                output.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Map.copyOf(output);
    }
}
