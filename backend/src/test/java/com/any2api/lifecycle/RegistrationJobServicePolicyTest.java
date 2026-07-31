package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegistrationJobServicePolicyTest {
    @Test
    void singleIdentityProvidersCannotCreateReplacementMailboxes() {
        assertThat(RegistrationJobService.effectiveMaxAttempts(
            9, 3, RegistrationAttemptMode.SINGLE_IDENTITY)).isEqualTo(3);
    }

    @Test
    void newIdentityProvidersKeepConfiguredAttemptBudget() {
        assertThat(RegistrationJobService.effectiveMaxAttempts(
            9, 3, RegistrationAttemptMode.NEW_IDENTITY)).isEqualTo(9);
    }
}
