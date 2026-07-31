package com.any2api.provider.qwen;

import com.any2api.account.LeasedProviderAccount;

record QwenCredential(
    String token,
    String userId,
    String userAgent,
    String browserProfile
) {
    QwenCredential(String token, String userId) {
        this(token, userId, "", "chrome146");
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
                    value.path("browser_profile").asText("chrome146").trim());
            }
        }
        throw new IllegalStateException("Qwen account credential requires token");
    }
}
