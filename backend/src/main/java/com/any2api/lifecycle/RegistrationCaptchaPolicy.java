package com.any2api.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public record RegistrationCaptchaPolicy(
    boolean aiEnabled,
    AiMode aiMode,
    String aiInternalModel,
    List<String> aiInternalModels
) {
    public static final String DEFAULT_INTERNAL_MODEL = "";
    public static final List<String> DEFAULT_INTERNAL_MODELS = List.of();

    public RegistrationCaptchaPolicy(boolean aiEnabled, AiMode aiMode, String aiInternalModel) {
        this(aiEnabled, aiMode,
            aiInternalModel == null ? DEFAULT_INTERNAL_MODEL : aiInternalModel.trim(),
            normalizeModels(aiInternalModel == null || aiInternalModel.isBlank() ? null : List.of(aiInternalModel.trim())));
    }

    public RegistrationCaptchaPolicy(boolean aiEnabled, AiMode aiMode) {
        this(aiEnabled, aiMode, DEFAULT_INTERNAL_MODEL, List.of());
    }

    public static RegistrationCaptchaPolicy resolve(Boolean enabled, AiMode mode) {
        return resolve(enabled, mode, DEFAULT_INTERNAL_MODEL, null);
    }

    public static RegistrationCaptchaPolicy resolve(Boolean enabled, AiMode mode, String internalModel) {
        return resolve(enabled, mode, internalModel, null);
    }

    public static RegistrationCaptchaPolicy resolve(
        Boolean enabled,
        AiMode mode,
        String internalModel,
        List<String> internalModels
    ) {
        var models = normalizeModels(internalModels);
        var single = internalModel == null ? DEFAULT_INTERNAL_MODEL : internalModel.trim();
        if (models.isEmpty() && !single.isEmpty()) {
            models = List.of(single);
        } else if (!models.isEmpty() && single.isEmpty()) {
            single = models.get(0);
        }
        return new RegistrationCaptchaPolicy(
            enabled == null || enabled,
            mode == null ? AiMode.INTERNAL : mode,
            single,
            models);
    }

    public static RegistrationCaptchaPolicy from(JsonNode request) {
        var captcha = request == null ? null : request.path("captcha");
        if (captcha == null || !captcha.isObject()) return resolve(null, null, null, null);
        var enabled = captcha.has("ai_enabled")
            ? captcha.path("ai_enabled").asBoolean() : true;
        var rawMode = captcha.path("ai_mode").asText("internal").trim();
        var rawModel = captcha.has("ai_internal_model")
            ? captcha.path("ai_internal_model").asText("").trim()
            : captcha.path("internal_model").asText("").trim();
        List<String> rawModels = null;
        if (captcha.has("ai_internal_models") && captcha.path("ai_internal_models").isArray()) {
            rawModels = new ArrayList<>();
            for (var item : captcha.path("ai_internal_models")) {
                var text = item.asText("").trim();
                if (!text.isEmpty()) {
                    rawModels.add(text);
                }
            }
        }
        try {
            return resolve(enabled, AiMode.valueOf(rawMode.toUpperCase(Locale.ROOT)), rawModel, rawModels);
        } catch (IllegalArgumentException error) {
            return resolve(enabled, null, rawModel, rawModels);
        }
    }

    public ObjectNode toWire(ObjectMapper mapper) {
        var node = mapper.createObjectNode()
            .put("ai_enabled", aiEnabled)
            .put("ai_mode", aiMode.name().toLowerCase(Locale.ROOT));
        if (aiInternalModel != null && !aiInternalModel.isBlank()) {
            node.put("ai_internal_model", aiInternalModel);
        }
        if (aiInternalModels != null && !aiInternalModels.isEmpty()) {
            var array = node.putArray("ai_internal_models");
            for (var m : aiInternalModels) {
                array.add(m);
            }
        }
        return node;
    }

    private static List<String> normalizeModels(List<String> input) {
        if (input == null || input.isEmpty()) return List.of();
        var list = new ArrayList<String>();
        for (var item : input) {
            if (item != null) {
                var trimmed = item.trim();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        return List.copyOf(list);
    }

    public enum AiMode { AUTO, INTERNAL, EXTERNAL }
}
