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
    int attemptTimeoutSeconds,
    int flowMaxAttempts,
    int maxConsecutiveFailureBatches,
    RegistrationProxyPolicy proxyPolicy,
    boolean headless,
    String mailDomain,
    String mailProvider,
    boolean aiCaptchaEnabled,
    RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
    String aiInternalModel,
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
) {
    public RegistrationJobView(
        UUID id, String providerId, String status,
        int target, int maxAttempts, int concurrency,
        int attemptIntervalSeconds, int roundIntervalSeconds,
        int attemptTimeoutSeconds, int flowMaxAttempts,
        int maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy, boolean headless,
        String mailDomain, boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
        int attempts, int successCount, int failureCount,
        boolean cancelRequested, String lastErrorClass,
        String lastErrorCode, String lastErrorStage,
        String lastErrorDetail, String lastErrorCorrelationId,
        JsonNode result, Instant createdAt, Instant updatedAt,
        Instant finishedAt
    ) {
        this(id, providerId, status, target, maxAttempts, concurrency,
            attemptIntervalSeconds, roundIntervalSeconds, attemptTimeoutSeconds,
            flowMaxAttempts, maxConsecutiveFailureBatches, proxyPolicy, headless,
            mailDomain, null, aiCaptchaEnabled, aiCaptchaMode, "",
            attempts, successCount, failureCount, cancelRequested, lastErrorClass,
            lastErrorCode, lastErrorStage, lastErrorDetail, lastErrorCorrelationId,
            result, createdAt, updatedAt, finishedAt);
    }

    public RegistrationJobView(
        UUID id, String providerId, String status,
        int target, int maxAttempts, int concurrency,
        int attemptIntervalSeconds, int roundIntervalSeconds,
        int attemptTimeoutSeconds, int flowMaxAttempts,
        int maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy, boolean headless,
        String mailDomain, String mailProvider, boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
        int attempts, int successCount, int failureCount,
        boolean cancelRequested, String lastErrorClass,
        String lastErrorCode, String lastErrorStage,
        String lastErrorDetail, String lastErrorCorrelationId,
        JsonNode result, Instant createdAt, Instant updatedAt,
        Instant finishedAt
    ) {
        this(id, providerId, status, target, maxAttempts, concurrency,
            attemptIntervalSeconds, roundIntervalSeconds, attemptTimeoutSeconds,
            flowMaxAttempts, maxConsecutiveFailureBatches, proxyPolicy, headless,
            mailDomain, mailProvider, aiCaptchaEnabled, aiCaptchaMode, "",
            attempts, successCount, failureCount, cancelRequested, lastErrorClass,
            lastErrorCode, lastErrorStage, lastErrorDetail, lastErrorCorrelationId,
            result, createdAt, updatedAt, finishedAt);
    }
}
