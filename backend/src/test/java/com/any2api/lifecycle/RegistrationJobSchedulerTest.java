package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.account.AccountStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationJobSchedulerTest {

    @Test
    void stagesAccountsForARealProbeEvenWhenTheWorkerClaimsReadiness() {
        var admission = RegistrationJobScheduler.registrationAdmission(true);

        assertThat(admission.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(admission.enabled()).isFalse();
        assertThat(admission.workerClaimedReady()).isTrue();
    }

    @Test
    void successfulRegistrationUsesShortJitterWhileFailuresBackOff() {
        var jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        var successDelay = RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 12, false);
        var failureDelay = RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 12, true);

        assertThat(successDelay).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(failureDelay).isGreaterThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void registrationExecutionGuardOutlivesAttemptAndLeaseRenewals() {
        assertThat(RegistrationJobScheduler.AUTOMATION_ATTEMPT_TIMEOUT)
            .isGreaterThan(RegistrationJobScheduler.LEASE_TTL.multipliedBy(2));
        assertThat(RegistrationJobScheduler.POLL_EXECUTION_TIMEOUT)
            .isGreaterThan(RegistrationJobScheduler.AUTOMATION_ATTEMPT_TIMEOUT);
        assertThat(RegistrationJobScheduler.LEASE_RENEW_INTERVAL)
            .isLessThan(RegistrationJobScheduler.LEASE_TTL);
    }
}
