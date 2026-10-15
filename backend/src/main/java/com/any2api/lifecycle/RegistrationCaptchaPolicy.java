package com.any2api.lifecycle;

import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public record RegistrationCaptchaPolicy(boolean aiEnabled, AiMode aiMode, String aiInternalModel) {
    public static final String DEFAULT_INTERNAL_MODEL = "";

    public RegistrationCaptchaPolicy(boolean aiEnabled, AiMode aiMode) {
        this(aiEnabled, aiMode, DEFAULT_INTERNAL_MODEL);
    }

    public static RegistrationCaptchaPolicy resolve(Boolean enabled, AiMode mode) {
        return resolve(enabled, mode, DEFAULT_INTERNAL_MODEL);
    }

    public static RegistrationCaptchaPolicy resolve(Boolean enabled, AiMode mode, String internalModel) {
        return new RegistrationCaptchaPolicy(
            enabled == null || enabled,
            mode == null ? AiMode.INTERNAL : mode,
            internalModel == null ? DEFAULT_INTERNAL_MODEL : internalModel.trim());
    }

    public static RegistrationCaptchaPolicy from(JsonNode request) {
        var captcha = request == null ? null : request.path("captcha");
        if (captcha == null || !captcha.isObject()) return resolve(null, null, null);
        var enabled = captcha.has("ai_enabled")
            ? captcha.path("ai_enabled").asBoolean() : true;
        var rawMode = captcha.path("ai_mode").asText("internal").trim();
        var rawModel = captcha.has("ai_internal_model")
            ? captcha.path("ai_internal_model").asText("").trim()
            : captcha.path("internal_model").asText("").trim();
        try {
            return resolve(enabled, AiMode.valueOf(rawMode.toUpperCase(Locale.ROOT)), rawModel);
        } catch (IllegalArgumentException error) {
            return resolve(enabled, null, rawModel);
        }
    }

    public ObjectNode toWire(ObjectMapper mapper) {
        var node = mapper.createObjectNode()
            .put("ai_enabled", aiEnabled)
            .put("ai_mode", aiMode.name().toLowerCase(Locale.ROOT));
        if (aiInternalModel != null && !aiInternalModel.isBlank()) {
            node.put("ai_internal_model", aiInternalModel);
        }
        return node;
    }

    public enum AiMode { AUTO, INTERNAL, EXTERNAL }
}
