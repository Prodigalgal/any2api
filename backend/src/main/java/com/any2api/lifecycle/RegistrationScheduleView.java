package com.any2api.lifecycle;

import java.time.Instant;
import java.util.UUID;

public record RegistrationScheduleView(
    UUID id,
    String name,
    String providerId,
    RegistrationScheduleService.ScheduleType scheduleType,
    Integer intervalMinutes,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    UUID lastJobId,
    RegistrationJobService.CreateCommand job,
    String lastError,
    Instant createdAt,
    Instant updatedAt
) {
}
