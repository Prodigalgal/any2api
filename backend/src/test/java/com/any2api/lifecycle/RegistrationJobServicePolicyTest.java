package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegistrationJobServicePolicyTest {
    @Test
    void singleIdentityProvidersKeepReplacementIdentityBudgetAfterWorkerExhaustion() {
        assertThat(RegistrationJobService.effectiveMaxAttempts(
            9, RegistrationAttemptMode.SINGLE_IDENTITY)).isEqualTo(9);
    }

    @Test
    void newIdentityProvidersKeepConfiguredAttemptBudget() {
        assertThat(RegistrationJobService.effectiveMaxAttempts(
            9, RegistrationAttemptMode.NEW_IDENTITY)).isEqualTo(9);
    }

    @Test
    void captchaPolicyDefaultsToEnabledInternalSolver() {
        var policy = RegistrationCaptchaPolicy.resolve(null, null);

        assertThat(policy.aiEnabled()).isTrue();
        assertThat(policy.aiMode()).isEqualTo(RegistrationCaptchaPolicy.AiMode.INTERNAL);
    }

    @Test
    void captchaPolicyRoundTripsTaskWireFormat() {
        var mapper = new ObjectMapper();
        var request = mapper.createObjectNode();
        request.set("captcha", RegistrationCaptchaPolicy.resolve(
            false, RegistrationCaptchaPolicy.AiMode.EXTERNAL).toWire(mapper));

        var policy = RegistrationCaptchaPolicy.from(request);

        assertThat(policy.aiEnabled()).isFalse();
        assertThat(policy.aiMode()).isEqualTo(RegistrationCaptchaPolicy.AiMode.EXTERNAL);
    }
}
