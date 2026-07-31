package com.any2api.provider.mimo;

import com.any2api.account.LeasedProviderAccount;
import java.util.Map;

record MimoCredential(
    String serviceToken,
    String userId,
    String phase,
    String userAgent,
    String browserProfile
) {
    static MimoCredential from(LeasedProviderAccount account) {
        var value = account.credential();
        var credential = new MimoCredential(
            value.path("service_token").asText("").trim(),
            value.path("user_id").asText("").trim(),
            value.path("xiaomichatbot_ph").asText("").trim(),
            value.path("user_agent").asText("").trim(),
            value.path("browser_profile").asText("chrome146").trim());
        if (credential.serviceToken.isBlank() || credential.userId.isBlank()
            || credential.phase.isBlank()) {
            throw new IllegalStateException(
                "MiMo account credential requires service_token, user_id and xiaomichatbot_ph");
        }
        return credential;
    }

    String cookie() {
        return "serviceToken=" + serviceToken + "; userId=" + userId
            + "; xiaomichatbot_ph=" + phase;
    }

    Map<String, String> cookies() {
        return Map.of(
            "serviceToken", serviceToken,
            "userId", userId,
            "xiaomichatbot_ph", phase);
    }
}
