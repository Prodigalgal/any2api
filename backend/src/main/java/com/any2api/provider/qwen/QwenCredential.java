package com.any2api.provider.qwen;

import com.any2api.account.LeasedProviderAccount;
import java.util.LinkedHashMap;
import java.util.Map;

record QwenCredential(
    String token,
    String userId,
    String userAgent,
    String browserProfile,
    Map<String, String> cookies
) {
    QwenCredential(String token, String userId) {
        this(token, userId, "", "chrome146", Map.of());
    }

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
                    cookies(value));
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
