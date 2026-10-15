package com.any2api.api.admin;

import com.any2api.lifecycle.RegistrationCaptchaPolicy;
import com.any2api.lifecycle.RegistrationJobService;
import com.any2api.lifecycle.RegistrationProxyPolicy;

public record RegistrationJobRequest(
    String providerId,
    Integer target,
    Integer maxAttempts,
    Integer concurrency,
    Integer attemptIntervalSeconds,
    Integer roundIntervalSeconds,
    Integer attemptTimeoutSeconds,
    Integer flowMaxAttempts,
    Integer maxConsecutiveFailureBatches,
    RegistrationProxyPolicy proxyPolicy,
    Boolean headless,
    String mailDomain,
    String mailProvider,
    Boolean aiCaptchaEnabled,
    RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
    String aiInternalModel,
    String idempotencyKey
) {
    public RegistrationJobRequest(
        String providerId,
        Integer target,
        Integer maxAttempts,
        Integer concurrency,
        Integer attemptIntervalSeconds,
        Integer roundIntervalSeconds,
        Integer attemptTimeoutSeconds,
        Integer flowMaxAttempts,
        Integer maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy,
        Boolean headless,
        String mailDomain,
        Boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
        String idempotencyKey
    ) {
        this(providerId, target, maxAttempts, concurrency, attemptIntervalSeconds,
            roundIntervalSeconds, attemptTimeoutSeconds, flowMaxAttempts,
            maxConsecutiveFailureBatches, proxyPolicy, headless, mailDomain, null,
            aiCaptchaEnabled, aiCaptchaMode, null, idempotencyKey);
    }

    public RegistrationJobRequest(
        String providerId,
        Integer target,
        Integer maxAttempts,
        Integer concurrency,
        Integer attemptIntervalSeconds,
        Integer roundIntervalSeconds,
        Integer attemptTimeoutSeconds,
        Integer flowMaxAttempts,
        Integer maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy,
        Boolean headless,
        String mailDomain,
        String mailProvider,
        Boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
        String idempotencyKey
    ) {
        this(providerId, target, maxAttempts, concurrency, attemptIntervalSeconds,
            roundIntervalSeconds, attemptTimeoutSeconds, flowMaxAttempts,
            maxConsecutiveFailureBatches, proxyPolicy, headless, mailDomain, mailProvider,
            aiCaptchaEnabled, aiCaptchaMode, null, idempotencyKey);
    }

    public RegistrationJobService.CreateCommand toCommand(String overrideIdempotencyKey) {
        return new RegistrationJobService.CreateCommand(
            providerId, target, maxAttempts, concurrency, attemptIntervalSeconds,
            roundIntervalSeconds, attemptTimeoutSeconds, flowMaxAttempts,
            maxConsecutiveFailureBatches, proxyPolicy, headless, mailDomain, mailProvider,
            aiCaptchaEnabled, aiCaptchaMode, aiInternalModel,
            overrideIdempotencyKey == null ? idempotencyKey : overrideIdempotencyKey);
    }
}
