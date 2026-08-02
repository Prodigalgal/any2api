package com.any2api.provider.deepseek;

import com.any2api.account.LeasedProviderAccount;

record DeepseekCredential(
    String token,
    String deviceId,
    String userAgent,
    String browserProfile
) {
    static DeepseekCredential from(LeasedProviderAccount account) {
        var value = account.credential();
        var credential = new DeepseekCredential(
            value.path("token").asText(value.path("access_token").asText("")).trim(),
            value.path("device_id").asText("").trim(),
            value.path("user_agent").asText("").trim(),
            value.path("browser_profile").asText("").trim());
        if (credential.token().isBlank() || credential.deviceId().isBlank()) {
            throw new IllegalStateException(
                "DeepSeek account credential requires token and device_id");
        }
        return credential;
    }
}
