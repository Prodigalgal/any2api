package com.any2api.provider.glm;

import com.any2api.account.LeasedProviderAccount;
import tools.jackson.databind.JsonNode;

record GlmCredential(String token, String userId) {
    static GlmCredential from(LeasedProviderAccount account) {
        var source = account.credential();
        var credential = new GlmCredential(
            first(source, "token", "access_token", "jwt"),
            first(source, "user_id", "userId", "id"));
        if (credential.token.isBlank() || credential.userId.isBlank()) {
            throw new IllegalStateException(
                "GLM account credential requires token and user_id");
        }
        return credential;
    }

    private static String first(JsonNode source, String... fields) {
        for (var field : fields) {
            var value = source.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
