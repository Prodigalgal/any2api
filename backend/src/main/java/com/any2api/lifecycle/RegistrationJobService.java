package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegistrationJobService {
    private static final int MAX_TARGET = 1000;
    private static final int MAX_CONCURRENCY = 8;

    private final JdbcClient jdbc;
    private final ProviderRegistry providers;
    private final AutomationProviderCatalog automationProviders;
    private final ObjectMapper mapper;

    public RegistrationJobService(
        JdbcClient jdbc,
        ProviderRegistry providers,
        AutomationProviderCatalog automationProviders,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.automationProviders = automationProviders;
        this.mapper = mapper;
    }

    @Transactional
    public RegistrationJobView create(CreateCommand command) {
        var provider = providers.require(command.providerId());
        if (provider.manifest().capabilities().getOrDefault(
            ProviderCapability.REGISTRATION, SupportLevel.UNSUPPORTED) == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException("provider does not support registration");
        }
        if (!automationProviders.supports(command.providerId(), AutomationOperation.REGISTER)) {
            throw new IllegalArgumentException(
                "automation provider does not support registration: " + command.providerId());
        }
        var target = bounded(command.target(), 1, MAX_TARGET, 1);
        var attempts = bounded(command.maxAttempts(), target, target * 10, target * 3);
        attempts = effectiveMaxAttempts(
            attempts, target, automationProviders.registrationAttemptMode(command.providerId()));
        var concurrency = bounded(command.concurrency(), 1, MAX_CONCURRENCY, 1);
        var key = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
            ? "registration:" + command.providerId() + ":" + UUID.randomUUID()
            : command.idempotencyKey().trim();
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO registration_jobs(
                id, provider_id, status, idempotency_key, requested, target,
                concurrency, request, result, next_attempt_at)
            VALUES (:id, :provider, 'PENDING', :key, :requested, :target,
                    :concurrency, '{}'::jsonb, '{"account_ids":[]}'::jsonb, CURRENT_TIMESTAMP)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
            .param("id", id)
            .param("provider", command.providerId())
            .param("key", key)
            .param("requested", attempts)
            .param("target", target)
            .param("concurrency", concurrency)
            .update();
        return jdbc.sql("SELECT * FROM registration_jobs WHERE idempotency_key = :key")
            .param("key", key).query(this::map).single();
    }

    static int effectiveMaxAttempts(
        int configuredAttempts,
        int target,
        RegistrationAttemptMode mode
    ) {
        return mode == RegistrationAttemptMode.SINGLE_IDENTITY ? target : configuredAttempts;
    }

    @Transactional(readOnly = true)
    public List<RegistrationJobView> list(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            providers.requirePlugin(providerId);
            return jdbc.sql("""
                SELECT * FROM registration_jobs
                WHERE provider_id = :provider ORDER BY created_at DESC LIMIT 200
                """).param("provider", providerId).query(this::map).list();
        }
        return jdbc.sql("SELECT * FROM registration_jobs ORDER BY created_at DESC LIMIT 200")
            .query(this::map).list();
    }

    @Transactional(readOnly = true)
    public RegistrationJobView get(UUID id) {
        return jdbc.sql("SELECT * FROM registration_jobs WHERE id = :id")
            .param("id", id).query(this::map).optional()
            .orElseThrow(() -> new IllegalArgumentException("unknown registration job: " + id));
    }

    @Transactional
    public RegistrationJobView cancel(UUID id) {
        var updated = jdbc.sql("""
            UPDATE registration_jobs SET cancel_requested = TRUE,
                status = CASE WHEN status = 'PENDING' THEN 'CANCELLED' ELSE status END,
                finished_at = CASE WHEN status = 'PENDING' THEN CURRENT_TIMESTAMP ELSE finished_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """).param("id", id).update();
        if (updated != 1) throw new IllegalArgumentException("unknown registration job: " + id);
        return get(id);
    }

    private static int bounded(Integer value, int minimum, int maximum, int fallback) {
        var result = value == null ? fallback : value;
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("registration job value is outside allowed range");
        }
        return result;
    }

    private RegistrationJobView map(ResultSet row, int ignored) throws SQLException {
        var rawResult = row.getString("result");
        return new RegistrationJobView(
            row.getObject("id", UUID.class), row.getString("provider_id"), row.getString("status"),
            row.getInt("target"), row.getInt("requested"), row.getInt("concurrency"),
            row.getInt("attempts"), row.getInt("success_count"), row.getInt("failure_count"),
            row.getBoolean("cancel_requested"), row.getString("last_error_class"),
            rawResult == null ? null : mapper.readTree(rawResult),
            PostgresResultValues.instant(row, "created_at"),
            PostgresResultValues.instant(row, "updated_at"),
            PostgresResultValues.instant(row, "finished_at"));
    }

    public record CreateCommand(
        String providerId, Integer target, Integer maxAttempts,
        Integer concurrency, String idempotencyKey
    ) {
        public CreateCommand {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("provider_id is required");
            }
        }
    }
}
