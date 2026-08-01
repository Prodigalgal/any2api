package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
