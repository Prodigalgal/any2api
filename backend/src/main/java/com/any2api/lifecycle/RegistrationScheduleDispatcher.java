package com.any2api.lifecycle;

import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistrationScheduleDispatcher {
    private static final int CLAIM_LIMIT = 10;

    private final RegistrationScheduleService schedules;
    private final RegistrationJobService jobs;

    public RegistrationScheduleDispatcher(
        RegistrationScheduleService schedules,
        RegistrationJobService jobs
    ) {
        this.schedules = schedules;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${any2api.lifecycle.registration-schedule-poll-interval:10s}")
    public void poll() {
        var owner = "registration-schedule:" + UUID.randomUUID();
        for (var schedule : schedules.claimDue(owner, CLAIM_LIMIT)) {
            dispatch(schedule, owner);
        }
    }

    private void dispatch(RegistrationScheduleService.Claim schedule, String owner) {
        try {
            var job = jobs.create(schedule.job().withIdempotencyKey(idempotencyKey(schedule)));
            schedules.complete(schedule, owner, job.id(), Instant.now());
        } catch (RuntimeException error) {
            schedules.fail(schedule, owner, error);
        }
    }

    static String idempotencyKey(RegistrationScheduleService.Claim schedule) {
        return "registration-schedule:" + schedule.id() + ":"
            + schedule.scheduledFor().toEpochMilli();
    }
}
