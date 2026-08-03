package com.any2api.api.admin;

import com.any2api.lifecycle.RegistrationJobService;
import com.any2api.lifecycle.RegistrationJobView;
import com.any2api.observability.OperationEventService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/registration-jobs")
public class AdminRegistrationJobController {
    private final RegistrationJobService jobs;
    private final OperationEventService events;

    public AdminRegistrationJobController(
        RegistrationJobService jobs,
        OperationEventService events
    ) {
        this.jobs = jobs;
        this.events = events;
    }

    @GetMapping
    public List<RegistrationJobView> list(
        @RequestParam(name = "provider", required = false) String provider
    ) {
        return jobs.list(provider);
    }

    @GetMapping("/{jobId}")
    public RegistrationJobView get(@PathVariable UUID jobId) { return jobs.get(jobId); }

    @GetMapping("/{jobId}/events")
    public List<OperationEventService.View> events(@PathVariable UUID jobId) {
        jobs.get(jobId);
        return events.list("REGISTRATION", jobId.toString());
    }

    @PostMapping
    public RegistrationJobView create(@RequestBody CreateRequest request) {
        return jobs.create(new RegistrationJobService.CreateCommand(
            request.providerId(), request.target(), request.maxAttempts(),
            request.concurrency(), request.attemptIntervalSeconds(),
            request.roundIntervalSeconds(), request.aiCaptchaEnabled(),
            request.aiCaptchaMode(), request.idempotencyKey()));
    }

    @PostMapping("/{jobId}/cancel")
    public RegistrationJobView cancel(@PathVariable UUID jobId) { return jobs.cancel(jobId); }

    public record CreateRequest(
        String providerId, Integer target, Integer maxAttempts,
        Integer concurrency, Integer attemptIntervalSeconds,
        Integer roundIntervalSeconds, Boolean aiCaptchaEnabled,
        com.any2api.lifecycle.RegistrationCaptchaPolicy.AiMode aiCaptchaMode,
        String idempotencyKey
    ) {}
}
