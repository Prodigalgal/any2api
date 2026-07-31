package com.any2api.provider.glm;

import com.any2api.account.LeasedProviderAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

record GlmCredential(
    String token,
    String userId,
    String userAgent,
    String browserProfile,
    Map<String, String> cookies,
    JsonNode deviceProfile
) {
    static GlmCredential from(LeasedProviderAccount account, GlmProperties properties) {
        var source = account.credential();
        var token = first(source, "token", "access_token", "jwt");
        var userId = first(source, "user_id", "userId", "id");
        if (token.isBlank() || userId.isBlank()) {
            throw new IllegalStateException("GLM account credential requires token and user_id");
        }
        var userAgent = source.path("user_agent").asText(properties.getUserAgent()).trim();
        var browserProfile = source.path("browser_profile")
            .asText(properties.getBrowserProfile()).trim();
        var cookies = new LinkedHashMap<String, String>();
        var values = source.path("cookies");
        if (values.isObject()) values.properties().forEach(entry -> {
            var value = entry.getValue().asText("").trim();
            if (validCookie(entry.getKey(), value)) cookies.put(entry.getKey(), value);
        });
        return new GlmCredential(token, userId, userAgent, browserProfile,
            Map.copyOf(cookies), source.path("device_profile").deepCopy());
    }

    private static String first(JsonNode source, String... fields) {
        for (var field : fields) {
            var value = source.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean validCookie(String name, String value) {
        return name.matches("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,128}")
            && !value.isBlank() && value.length() <= 8192
            && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }
}
