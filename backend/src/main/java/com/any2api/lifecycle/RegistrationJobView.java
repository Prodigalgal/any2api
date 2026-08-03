package com.any2api.lifecycle;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RegistrationJobView(
    UUID id,
    String providerId,
    String status,
    int target,
    int maxAttempts,
    int concurrency,
    int attemptIntervalSeconds,
    int roundIntervalSeconds,
    boolean aiCaptchaEnabled,
    RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
    int attempts,
    int successCount,
    int failureCount,
    boolean cancelRequested,
    String lastErrorClass,
    String lastErrorCode,
    String lastErrorStage,
    String lastErrorDetail,
    String lastErrorCorrelationId,
    JsonNode result,
    Instant createdAt,
    Instant updatedAt,
    Instant finishedAt
) {}
