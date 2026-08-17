package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.ProviderRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegistrationScheduleService {
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60;

    private final JdbcClient jdbc;
    private final RegistrationJobService jobs;
    private final ProviderRegistry providers;
    private final ObjectMapper mapper;

    public RegistrationScheduleService(
        JdbcClient jdbc,
        RegistrationJobService jobs,
        ProviderRegistry providers,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.providers = providers;
        this.mapper = mapper;
    }

    @Transactional
    public RegistrationScheduleView create(SaveCommand command) {
        var normalized = normalize(command);
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO registration_schedules(
                id, name, provider_id, schedule_type, interval_minutes,
                enabled, next_run_at, job_command)
            VALUES (
                :id, :name, :provider, :type, :interval,
                :enabled, :nextRun, CAST(:job AS jsonb))
            """)
            .param("id", id)
            .param("name", normalized.name())
            .param("provider", normalized.job().providerId())
            .param("type", normalized.scheduleType().name())
            .param("interval", normalized.intervalMinutes(), Types.INTEGER)
            .param("enabled", normalized.enabled())
            .param("nextRun", normalized.firstRunAt(), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("job", mapper.valueToTree(normalized.job()).toString())
            .update();
        return get(id);
    }

    @Transactional
    public RegistrationScheduleView update(UUID id, SaveCommand command) {
        get(id);
        var normalized = normalize(command);
        var updated = jdbc.sql("""
            UPDATE registration_schedules SET
                name = :name, provider_id = :provider, schedule_type = :type,
                interval_minutes = :interval, enabled = :enabled,
                next_run_at = :nextRun, job_command = CAST(:job AS jsonb),
                last_error = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
            """)
            .param("id", id)
            .param("name", normalized.name())
            .param("provider", normalized.job().providerId())
            .param("type", normalized.scheduleType().name())
            .param("interval", normalized.intervalMinutes(), Types.INTEGER)
            .param("enabled", normalized.enabled())
            .param("nextRun", normalized.firstRunAt(), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("job", mapper.valueToTree(normalized.job()).toString())
            .update();
        if (updated != 1) {
            throw new IllegalStateException("registration schedule is currently executing");
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public RegistrationSchedulePageView page(
        String providerId,
        Boolean enabled,
        int page,
        int size
    ) {
        if (page < 0 || size < 10 || size > 100) {
            throw new IllegalArgumentException("registration schedule page is outside allowed range");
        }
        var normalizedProvider = providerId == null || providerId.isBlank()
            ? null : providerId.trim();
        if (normalizedProvider != null) providers.requirePlugin(normalizedProvider);

        var predicates = new ArrayList<String>();
        var parameters = new HashMap<String, Object>();
        if (normalizedProvider != null) {
            predicates.add("provider_id = :provider");
            parameters.put("provider", normalizedProvider);
        }
        if (enabled != null) {
            predicates.add("enabled = :enabled");
            parameters.put("enabled", enabled);
        }
        var where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        var offset = Math.multiplyExact((long) page, size);
        var items = jdbc.sql("SELECT * FROM registration_schedules" + where
                + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
            .params(parameters).param("size", size).param("offset", offset)
            .query(this::map).list();
        var total = jdbc.sql("SELECT COUNT(*) FROM registration_schedules" + where)
            .params(parameters).query(Long.class).single();
        return RegistrationSchedulePageView.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public RegistrationScheduleView get(UUID id) {
        return jdbc.sql("SELECT * FROM registration_schedules WHERE id = :id")
            .param("id", id).query(this::map).optional()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown registration schedule: " + id));
    }

    @Transactional
    public RegistrationScheduleView setEnabled(UUID id, boolean enabled) {
        var current = get(id);
        if (enabled && current.nextRunAt() == null) {
            throw new IllegalArgumentException(
                "completed one-time registration schedule cannot be enabled");
        }
        var updated = jdbc.sql("""
            UPDATE registration_schedules SET enabled = :enabled,
                lease_owner = NULL, lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
            """)
            .param("id", id).param("enabled", enabled).update();
        if (updated != 1) {
            throw new IllegalStateException("registration schedule is currently executing");
        }
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        get(id);
        var deleted = jdbc.sql("""
            DELETE FROM registration_schedules
            WHERE id = :id
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
            """).param("id", id).update();
        if (deleted != 1) {
            throw new IllegalStateException("registration schedule is currently executing");
        }
    }

    @Transactional
    public java.util.List<Claim> claimDue(String owner, int limit) {
        if (owner == null || owner.isBlank() || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("invalid registration schedule claim");
        }
        return jdbc.sql("""
            WITH candidates AS (
                SELECT id FROM registration_schedules
                WHERE enabled = TRUE AND next_run_at <= CURRENT_TIMESTAMP
                  AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
                ORDER BY next_run_at, id
                FOR UPDATE SKIP LOCKED LIMIT :limit
            )
            UPDATE registration_schedules schedule SET
                lease_owner = :owner,
                lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                updated_at = CURRENT_TIMESTAMP
            FROM candidates WHERE schedule.id = candidates.id
            RETURNING schedule.*
            """)
            .param("limit", limit).param("owner", owner)
            .query((row, ignored) -> claim(row)).list();
    }

    @Transactional
    public void complete(Claim claim, String owner, UUID jobId, Instant completedAt) {
        var nextRun = nextRunAt(
            claim.scheduleType(), claim.scheduledFor(), claim.intervalMinutes(), completedAt);
        var updated = jdbc.sql("""
            UPDATE registration_schedules SET
                enabled = :enabled, next_run_at = :nextRun,
                last_run_at = :completedAt, last_job_id = :jobId,
                last_error = NULL, lease_owner = NULL, lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND lease_owner = :owner
            """)
            .param("enabled", nextRun != null)
            .param("nextRun", nextRun, Types.TIMESTAMP_WITH_TIMEZONE)
            .param("completedAt", completedAt, Types.TIMESTAMP_WITH_TIMEZONE)
            .param("jobId", jobId).param("id", claim.id()).param("owner", owner)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("registration schedule lease was lost");
        }
    }

    @Transactional
    public void fail(Claim claim, String owner, Throwable error) {
        var detail = error.getClass().getSimpleName() + ": "
            + String.valueOf(error.getMessage());
        if (detail.length() > 500) detail = detail.substring(0, 500);
        var updated = jdbc.sql("""
            UPDATE registration_schedules SET
                last_error = :error, lease_owner = NULL,
                lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND lease_owner = :owner
            """)
            .param("error", detail).param("id", claim.id()).param("owner", owner)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("registration schedule lease was lost");
        }
    }

    static Instant nextRunAt(
        ScheduleType scheduleType,
        Instant scheduledFor,
        Integer intervalMinutes,
        Instant completedAt
    ) {
        if (scheduleType == ScheduleType.ONCE) return null;
        var interval = java.time.Duration.ofMinutes(intervalMinutes);
        var elapsed = Math.max(0, java.time.Duration.between(
            scheduledFor, completedAt).toMinutes());
        var steps = elapsed / intervalMinutes + 1;
        return scheduledFor.plus(interval.multipliedBy(steps));
    }

    private SaveCommand normalize(SaveCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("registration schedule name is required");
        }
        var name = command.name().trim();
        if (name.length() > 120) {
            throw new IllegalArgumentException("registration schedule name is too long");
        }
        if (command.scheduleType() == null || command.firstRunAt() == null) {
            throw new IllegalArgumentException("registration schedule timing is required");
        }
        Integer interval = null;
        if (command.scheduleType() == ScheduleType.INTERVAL) {
            interval = command.intervalMinutes();
            if (interval == null || interval < MIN_INTERVAL_MINUTES
                || interval > MAX_INTERVAL_MINUTES) {
                throw new IllegalArgumentException(
                    "registration schedule interval is outside allowed range");
            }
        }
        var normalizedJob = jobs.normalize(command.job()).withIdempotencyKey(null);
        return new SaveCommand(
            name, command.scheduleType(), interval,
            command.enabled() == null || command.enabled(),
            command.firstRunAt(), normalizedJob);
    }

    private RegistrationScheduleView map(ResultSet row, int ignored) throws SQLException {
        return new RegistrationScheduleView(
            row.getObject("id", UUID.class), row.getString("name"),
            row.getString("provider_id"), ScheduleType.valueOf(row.getString("schedule_type")),
            (Integer) row.getObject("interval_minutes"), row.getBoolean("enabled"),
            PostgresResultValues.instant(row, "next_run_at"),
            PostgresResultValues.instant(row, "last_run_at"),
            row.getObject("last_job_id", UUID.class), readJob(row.getString("job_command")),
            row.getString("last_error"), PostgresResultValues.instant(row, "created_at"),
            PostgresResultValues.instant(row, "updated_at"));
    }

    private Claim claim(ResultSet row) throws SQLException {
        return new Claim(
            row.getObject("id", UUID.class), ScheduleType.valueOf(row.getString("schedule_type")),
            (Integer) row.getObject("interval_minutes"),
            PostgresResultValues.instant(row, "next_run_at"),
            readJob(row.getString("job_command")));
    }

    private RegistrationJobService.CreateCommand readJob(String value) throws SQLException {
        try {
            return mapper.readValue(value, RegistrationJobService.CreateCommand.class);
        } catch (JacksonException error) {
            throw new SQLException("registration schedule contains invalid job command", error);
        }
    }

    public enum ScheduleType { ONCE, INTERVAL }

    public record SaveCommand(
        String name,
        ScheduleType scheduleType,
        Integer intervalMinutes,
        Boolean enabled,
        Instant firstRunAt,
        RegistrationJobService.CreateCommand job
    ) {
    }

    public record Claim(
        UUID id,
        ScheduleType scheduleType,
        Integer intervalMinutes,
        Instant scheduledFor,
        RegistrationJobService.CreateCommand job
    ) {
    }
}
