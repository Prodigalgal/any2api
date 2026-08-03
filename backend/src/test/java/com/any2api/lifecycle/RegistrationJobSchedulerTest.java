package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.account.AccountStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegistrationJobSchedulerTest {

    @Test
    void stagesAccountsForARealProbeEvenWhenTheWorkerClaimsReadiness() {
        var admission = RegistrationJobScheduler.registrationAdmission(true);

        assertThat(admission.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(admission.enabled()).isFalse();
        assertThat(admission.workerClaimedReady()).isTrue();
    }

    @Test
    void onlyConsecutiveFullyFailedBatchesBackOff() {
        var jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        var successDelay = RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 0, false);
        var firstFailureDelay = RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 1, true);
        var sustainedFailureDelay = RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 6, true);

        assertThat(successDelay).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(firstFailureDelay).isBetween(Duration.ofSeconds(30), Duration.ofMinutes(1));
        assertThat(sustainedFailureDelay).isGreaterThanOrEqualTo(Duration.ofMinutes(15));
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

    @Test
    void configuredRoundIntervalIsPreservedAndFailureBackoffRemainsTheFloor() {
        var jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 0, false, Duration.ZERO)).isZero();
        assertThat(RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 0, false, Duration.ofMinutes(2)))
            .isBetween(Duration.ofMinutes(2), Duration.ofMinutes(2).plusSeconds(10));
        assertThat(RegistrationJobScheduler.nextRegistrationDelay(
            jobId, 1, true, Duration.ofMinutes(5)))
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void registrationPayloadPreservesCaptchaPolicyForTheProviderWorker() {
        var mapper = new ObjectMapper();
        var request = mapper.createObjectNode();
        request.set("captcha", RegistrationCaptchaPolicy.resolve(
            true, RegistrationCaptchaPolicy.AiMode.EXTERNAL).toWire(mapper));

        var payload = RegistrationJobScheduler.registrationPayload(request, mapper);

        assertThat(payload).containsKey("captcha");
        assertThat(mapper.valueToTree(payload.get("captcha")).path("ai_enabled").asBoolean())
            .isTrue();
        assertThat(mapper.valueToTree(payload.get("captcha")).path("ai_mode").asText())
            .isEqualTo("external");
    }
}
