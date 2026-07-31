package com.any2api.provider.minmax;

import com.any2api.account.LeasedProviderAccount;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

record MinmaxCredential(
    String token,
    String userId,
    String deviceId,
    String uuid,
    String opTicket,
    String agentId,
    JsonNode requestProfile,
    JsonNode deviceProfile,
    String userAgent
) {
    MinmaxCredential(
        String token,
        String userId,
        String deviceId,
        String uuid,
        String opTicket,
        String agentId
    ) {
        this(token, userId, deviceId, uuid, opTicket, agentId,
            MissingNode.getInstance(), MissingNode.getInstance(), "");
    }

    static MinmaxCredential from(LeasedProviderAccount account) {
        var value = account.credential();
        var token = value.path("token").asText(value.path("access_token").asText("")).trim();
        var userId = value.path("user_id").asText("").trim();
        if (token.isBlank() || userId.isBlank()) {
            throw new IllegalStateException("MinMax account credential requires token and user_id");
        }
        return new MinmaxCredential(
            token,
            userId,
            value.path("device_id").asText(stableDeviceId(account)).trim(),
            value.path("uuid").asText("").trim(),
            value.path("op_ticket").asText("").trim(),
            value.path("agent_id").asText("").trim(),
            value.path("request_profile").deepCopy(),
            value.path("device_profile").deepCopy(),
            value.path("user_agent").asText("").trim());
    }

    MinmaxProperties.RequestProfile effectiveProfile(MinmaxProperties.RequestProfile fallback) {
        return new MinmaxProperties.RequestProfile(
            text(requestProfile, "signature_salt", fallback.signatureSalt()),
            text(requestProfile, "yy_salt", fallback.yySalt()),
            fallback.devicePlatform(), fallback.bizId(), fallback.appId(),
            text(requestProfile, "version_code", fallback.versionCode()), fallback.language(),
            fallback.client(), fallback.region(),
            text(deviceProfile, "os_name", fallback.osName()),
            text(deviceProfile, "browser_name", fallback.browserName()),
            integer(deviceProfile, "device_memory", fallback.deviceMemory()),
            integer(deviceProfile, "cpu_core_num", fallback.cpuCoreCount()),
            text(deviceProfile, "browser_language", fallback.browserLanguage()),
            text(deviceProfile, "browser_platform", fallback.browserPlatform()),
            integer(deviceProfile, "screen_width", fallback.screenWidth()),
            integer(deviceProfile, "screen_height", fallback.screenHeight()));
    }

    int timezoneOffset(int fallback) {
        return integer(deviceProfile, "timezone_offset", fallback);
    }

    String effectiveUserAgent(String fallback) {
        return userAgent.isBlank() ? fallback : userAgent;
    }

    private static String text(JsonNode source, String name, String fallback) {
        var value = source.path(name).asText("").trim();
        return value.isBlank() || value.length() > 512 ? fallback : value;
    }

    private static int integer(JsonNode source, String name, int fallback) {
        var value = source.path(name);
        if (!value.isIntegralNumber() && !value.isTextual()) return fallback;
        try {
            var parsed = value.isIntegralNumber() ? value.intValue() : Integer.parseInt(value.asText());
            return parsed > 0 || "timezone_offset".equals(name) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stableDeviceId(LeasedProviderAccount account) {
        return Integer.toString(10_000_000
            + Math.floorMod(account.accountId().hashCode(), 90_000_000));
    }
}
