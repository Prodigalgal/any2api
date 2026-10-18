package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegistrationCaptchaPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectedInternalModelSurvivesWireRoundTrip() {
        var policy = RegistrationCaptchaPolicy.resolve(
            true, RegistrationCaptchaPolicy.AiMode.INTERNAL, " mimo/mimo-v2.6-flash ");
        var wire = mapper.createObjectNode().set("captcha", policy.toWire(mapper));

        assertThat(policy.aiInternalModel()).isEqualTo("mimo/mimo-v2.6-flash");
        assertThat(RegistrationCaptchaPolicy.from(wire)).isEqualTo(policy);
    }

    @Test
    void legacyPolicyKeepsDefaultRoutingAndAcceptsModelAlias() {
        var legacy = RegistrationCaptchaPolicy.resolve(null, null);
        assertThat(legacy.aiEnabled()).isTrue();
        assertThat(legacy.aiMode()).isEqualTo(RegistrationCaptchaPolicy.AiMode.INTERNAL);
        assertThat(legacy.toWire(mapper).has("ai_internal_model")).isFalse();

        var request = mapper.readTree("""
            {"captcha":{"ai_enabled":false,"ai_mode":"external",
                        "internal_model":" mimo/mimo-v2.6-pro "}}
            """);
        assertThat(RegistrationCaptchaPolicy.from(request)).isEqualTo(
            new RegistrationCaptchaPolicy(false,
                RegistrationCaptchaPolicy.AiMode.EXTERNAL, "mimo/mimo-v2.6-pro"));
    }

    @Test
    void orderedInternalModelsSurviveWireRoundTrip() {
        var models = java.util.List.of("mimo/mimo-v2.6-pro", "glm/GLM-5v-Turbo", "arena/gemini-2.5-flash");
        var policy = RegistrationCaptchaPolicy.resolve(
            true, RegistrationCaptchaPolicy.AiMode.INTERNAL, null, models);
        assertThat(policy.aiInternalModel()).isEqualTo("mimo/mimo-v2.6-pro");
        assertThat(policy.aiInternalModels()).containsExactlyElementsOf(models);

        var wire = mapper.createObjectNode().set("captcha", policy.toWire(mapper));
        var restored = RegistrationCaptchaPolicy.from(wire);
        assertThat(restored.aiInternalModel()).isEqualTo("mimo/mimo-v2.6-pro");
        assertThat(restored.aiInternalModels()).containsExactlyElementsOf(models);
    }
}
