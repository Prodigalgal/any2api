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
}
