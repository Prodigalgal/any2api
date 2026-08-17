package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationScheduleDispatcherTest {

    @Test
    void oneTimeScheduleHasNoNextRun() {
        assertThat(RegistrationScheduleService.nextRunAt(
            RegistrationScheduleService.ScheduleType.ONCE,
            Instant.parse("2026-08-11T01:00:00Z"), null,
            Instant.parse("2026-08-11T01:02:00Z"))).isNull();
    }

    @Test
    void intervalScheduleSkipsMissedWindowsWithoutDrifting() {
        assertThat(RegistrationScheduleService.nextRunAt(
            RegistrationScheduleService.ScheduleType.INTERVAL,
            Instant.parse("2026-08-11T01:00:00Z"), 30,
            Instant.parse("2026-08-11T02:12:00Z")))
            .isEqualTo(Instant.parse("2026-08-11T02:30:00Z"));
    }

    @Test
    void retryUsesStableScheduleAndOriginalWindowIdempotencyKey() {
        var claim = new RegistrationScheduleService.Claim(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            RegistrationScheduleService.ScheduleType.INTERVAL,
            60, Instant.parse("2026-08-11T01:00:00Z"), command());

        assertThat(RegistrationScheduleDispatcher.idempotencyKey(claim))
            .isEqualTo("registration-schedule:11111111-1111-1111-1111-111111111111:1786410000000");
    }

    private RegistrationJobService.CreateCommand command() {
        return new RegistrationJobService.CreateCommand(
            "qwen", 1, 3, 1, 0, 5, 2100, 3, 5,
            RegistrationProxyPolicy.PROVIDER_DEFAULT, true, null,
            true, RegistrationCaptchaPolicy.AiMode.INTERNAL, null);
    }
}
