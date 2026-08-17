package com.any2api.api.admin;

import com.any2api.lifecycle.RegistrationSchedulePageView;
import com.any2api.lifecycle.RegistrationScheduleService;
import com.any2api.lifecycle.RegistrationScheduleView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/registration-schedules")
public class AdminRegistrationScheduleController {
    private final RegistrationScheduleService schedules;

    public AdminRegistrationScheduleController(RegistrationScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/page")
    public RegistrationSchedulePageView page(
        @RequestParam(name = "provider", required = false) String provider,
        @RequestParam(name = "enabled", required = false) Boolean enabled,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return schedules.page(provider, enabled, page, size);
    }

    @GetMapping("/{scheduleId}")
    public RegistrationScheduleView get(@PathVariable UUID scheduleId) {
        return schedules.get(scheduleId);
    }

    @PostMapping
    public RegistrationScheduleView create(@RequestBody SaveRequest request) {
        return schedules.create(request.toCommand());
    }

    @PutMapping("/{scheduleId}")
    public RegistrationScheduleView update(
        @PathVariable UUID scheduleId,
        @RequestBody SaveRequest request
    ) {
        return schedules.update(scheduleId, request.toCommand());
    }

    @PatchMapping("/{scheduleId}/enabled")
    public RegistrationScheduleView setEnabled(
        @PathVariable UUID scheduleId,
        @RequestBody EnabledRequest request
    ) {
        return schedules.setEnabled(scheduleId, request.enabled());
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID scheduleId) {
        schedules.delete(scheduleId);
    }

    public record SaveRequest(
        String name,
        RegistrationScheduleService.ScheduleType scheduleType,
        Integer intervalMinutes,
        Boolean enabled,
        Instant firstRunAt,
        RegistrationJobRequest job
    ) {
        RegistrationScheduleService.SaveCommand toCommand() {
            if (job == null) {
                throw new IllegalArgumentException("registration job template is required");
            }
            return new RegistrationScheduleService.SaveCommand(
                name, scheduleType, intervalMinutes, enabled, firstRunAt,
                job.toCommand(null));
        }
    }

    public record EnabledRequest(boolean enabled) {
    }
}
