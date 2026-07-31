package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.account.AccountStatus;
import org.junit.jupiter.api.Test;

class RegistrationJobSchedulerTest {

    @Test
    void stagesAccountsForARealProbeEvenWhenTheWorkerClaimsReadiness() {
        var admission = RegistrationJobScheduler.registrationAdmission(true);

        assertThat(admission.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(admission.enabled()).isFalse();
        assertThat(admission.workerClaimedReady()).isTrue();
    }
}
