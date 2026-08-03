package com.any2api.lifecycle;

import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public record RegistrationCaptchaPolicy(boolean aiEnabled, AiMode aiMode) {
    static RegistrationCaptchaPolicy resolve(Boolean enabled, AiMode mode) {
        return new RegistrationCaptchaPolicy(
            enabled == null || enabled,
            mode == null ? AiMode.INTERNAL : mode);
    }

    static RegistrationCaptchaPolicy from(JsonNode request) {
        var captcha = request == null ? null : request.path("captcha");
        if (captcha == null || !captcha.isObject()) return resolve(null, null);
        var enabled = captcha.has("ai_enabled")
            ? captcha.path("ai_enabled").asBoolean() : true;
        var rawMode = captcha.path("ai_mode").asText("internal").trim();
        try {
            return resolve(enabled, AiMode.valueOf(rawMode.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException error) {
            return resolve(enabled, null);
        }
    }

    ObjectNode toWire(ObjectMapper mapper) {
        return mapper.createObjectNode()
            .put("ai_enabled", aiEnabled)
            .put("ai_mode", aiMode.name().toLowerCase(Locale.ROOT));
    }

    public enum AiMode { AUTO, INTERNAL, EXTERNAL }
}
