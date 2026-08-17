package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import com.any2api.settings.RuntimeSettingsService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegistrationJobService {
    private static final int MAX_TARGET = 1000;
    private static final int MAX_CONCURRENCY = 8;
    private static final int MAX_ATTEMPT_INTERVAL_SECONDS = 3600;
    private static final int MAX_ROUND_INTERVAL_SECONDS = 86400;
    private static final Set<String> JOB_STATUSES = Set.of(
        "PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");

    private final JdbcClient jdbc;
    private final ProviderRegistry providers;
    private final AutomationProviderCatalog automationProviders;
    private final ObjectMapper mapper;
    private final RuntimeSettingsService runtimeSettings;

    public RegistrationJobService(
        JdbcClient jdbc,
        ProviderRegistry providers,
        AutomationProviderCatalog automationProviders,
        ObjectMapper mapper,
        RuntimeSettingsService runtimeSettings
    ) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.automationProviders = automationProviders;
        this.mapper = mapper;
        this.runtimeSettings = runtimeSettings;
    }

    @Transactional
    public RegistrationJobView create(CreateCommand command) {
        var normalized = normalize(command);
        var captcha = RegistrationCaptchaPolicy.resolve(
            normalized.aiCaptchaEnabled(), normalized.aiCaptchaMode());
        var request = mapper.createObjectNode();
        request.set("captcha", captcha.toWire(mapper));
        request.put("attempt_timeout_seconds", normalized.attemptTimeoutSeconds());
        request.put("flow_max_attempts", normalized.flowMaxAttempts());
        request.put("max_consecutive_failure_batches",
            normalized.maxConsecutiveFailureBatches());
        request.put("proxy_policy", normalized.proxyPolicy().name());
        request.put("headless", normalized.headless());
        if (normalized.mailDomain() != null && !normalized.mailDomain().isBlank()) {
            request.put("mail_domain", normalized.mailDomain());
        }
        var key = normalized.idempotencyKey() == null || normalized.idempotencyKey().isBlank()
            ? "registration:" + normalized.providerId() + ":" + UUID.randomUUID()
            : normalized.idempotencyKey();
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO registration_jobs(
                id, provider_id, status, idempotency_key, requested, target,
                concurrency, attempt_interval_seconds, round_interval_seconds,
                request, result, next_attempt_at)
            VALUES (:id, :provider, 'PENDING', :key, :requested, :target,
                    :concurrency, :attemptInterval, :roundInterval,
                    CAST(:request AS jsonb), '{"account_ids":[]}'::jsonb, CURRENT_TIMESTAMP)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
            .param("id", id)
            .param("provider", normalized.providerId())
            .param("key", key)
            .param("requested", normalized.maxAttempts())
            .param("target", normalized.target())
            .param("concurrency", normalized.concurrency())
            .param("attemptInterval", normalized.attemptIntervalSeconds())
            .param("roundInterval", normalized.roundIntervalSeconds())
            .param("request", request.toString())
            .update();
        return jdbc.sql("SELECT * FROM registration_jobs WHERE idempotency_key = :key")
            .param("key", key).query(this::map).single();
    }

    public CreateCommand normalize(CreateCommand command) {
        var provider = providers.require(command.providerId());
        if (provider.manifest().capabilities().getOrDefault(
            ProviderCapability.REGISTRATION, SupportLevel.UNSUPPORTED) == SupportLevel.UNSUPPORTED) {
            throw new IllegalArgumentException("provider does not support registration");
        }
        if (!automationProviders.supports(command.providerId(), AutomationOperation.REGISTER)) {
            throw new IllegalArgumentException(
                "automation provider does not support registration: " + command.providerId());
        }
        var defaults = runtimeSettings.registrationDefaults();
        var target = bounded(command.target(), 1, MAX_TARGET, defaults.target());
        var attempts = bounded(command.maxAttempts(), target, target * 10,
            Math.max(target, defaults.maxAttempts()));
        attempts = effectiveMaxAttempts(
            attempts, automationProviders.registrationAttemptMode(command.providerId()));
        var concurrency = bounded(
            command.concurrency(), 1, MAX_CONCURRENCY, defaults.concurrency());
        var attemptIntervalSeconds = bounded(
            command.attemptIntervalSeconds(), 0, MAX_ATTEMPT_INTERVAL_SECONDS,
            defaults.attemptIntervalSeconds());
        var roundIntervalSeconds = bounded(
            command.roundIntervalSeconds(), 0, MAX_ROUND_INTERVAL_SECONDS,
            defaults.roundIntervalSeconds());
        var attemptTimeoutSeconds = bounded(
            command.attemptTimeoutSeconds(), 60, 3600, defaults.attemptTimeoutSeconds());
        var flowMaxAttempts = bounded(
            command.flowMaxAttempts(), 1, 10, defaults.flowMaxAttempts());
        var maxConsecutiveFailureBatches = bounded(
            command.maxConsecutiveFailureBatches(), 1, 20,
            defaults.maxConsecutiveFailureBatches());
        var proxyPolicy = command.proxyPolicy() == null
            ? defaults.proxyPolicy() : command.proxyPolicy();
        var headless = command.headless() == null ? defaults.headless() : command.headless();
        var captcha = RegistrationCaptchaPolicy.resolve(
            command.aiCaptchaEnabled() == null
                ? defaults.aiCaptchaEnabled() : command.aiCaptchaEnabled(),
            command.aiCaptchaMode() == null ? defaults.aiCaptchaMode() : command.aiCaptchaMode());
        var mailDomain = command.mailDomain() == null || command.mailDomain().isBlank()
            ? null : command.mailDomain().trim().toLowerCase();
        var idempotencyKey = command.idempotencyKey() == null
            || command.idempotencyKey().isBlank()
            ? null : command.idempotencyKey().trim();
        return new CreateCommand(
            command.providerId().trim(), target, attempts, concurrency,
            attemptIntervalSeconds, roundIntervalSeconds, attemptTimeoutSeconds,
            flowMaxAttempts, maxConsecutiveFailureBatches, proxyPolicy, headless,
            mailDomain, captcha.aiEnabled(), captcha.aiMode(), idempotencyKey);
    }

    static int effectiveMaxAttempts(
        int configuredAttempts,
        RegistrationAttemptMode mode
    ) {
        // One scheduler attempt owns one mailbox identity. Provider-internal browser retries
        // reuse that identity and are not counted here.
        return switch (mode) {
            case NEW_IDENTITY, SINGLE_IDENTITY -> configuredAttempts;
        };
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
    public RegistrationJobPageView page(
        String providerId,
        String status,
        int page,
        int size
    ) {
        if (page < 0 || size < 10 || size > 100) {
            throw new IllegalArgumentException("registration job page is outside allowed range");
        }
        var normalizedProvider = providerId == null || providerId.isBlank()
            ? null : providerId.trim();
        if (normalizedProvider != null) providers.requirePlugin(normalizedProvider);
        var normalizedStatus = status == null || status.isBlank()
            ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !JOB_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("unknown registration job status: " + status);
        }

        var predicates = new ArrayList<String>();
        var parameters = new HashMap<String, Object>();
        if (normalizedProvider != null) {
            predicates.add("provider_id = :provider");
            parameters.put("provider", normalizedProvider);
        }
        if (normalizedStatus != null) {
            predicates.add("status = :status");
            parameters.put("status", normalizedStatus);
        }
        var where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        var offset = Math.multiplyExact((long) page, size);
        var items = jdbc.sql("SELECT * FROM registration_jobs" + where
                + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
            .params(parameters).param("size", size).param("offset", offset)
            .query(this::map).list();
        var total = jdbc.sql("SELECT COUNT(*) FROM registration_jobs" + where)
            .params(parameters).query(Long.class).single();
        return RegistrationJobPageView.of(items, total, page, size);
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
        var rawRequest = row.getString("request");
        var request = rawRequest == null ? mapper.createObjectNode() : mapper.readTree(rawRequest);
        var captcha = RegistrationCaptchaPolicy.from(request);
        var rawResult = row.getString("result");
        return new RegistrationJobView(
            row.getObject("id", UUID.class), row.getString("provider_id"), row.getString("status"),
            row.getInt("target"), row.getInt("requested"), row.getInt("concurrency"),
            row.getInt("attempt_interval_seconds"), row.getInt("round_interval_seconds"),
            request.path("attempt_timeout_seconds").asInt(2100),
            request.path("flow_max_attempts").asInt(3),
            request.path("max_consecutive_failure_batches").asInt(5),
            proxyPolicy(request.path("proxy_policy").asText("PROVIDER_DEFAULT")),
            request.path("headless").asBoolean(true),
            request.path("mail_domain").asText(""),
            captcha.aiEnabled(), captcha.aiMode(),
            row.getInt("attempts"), row.getInt("success_count"), row.getInt("failure_count"),
            row.getBoolean("cancel_requested"), row.getString("last_error_class"),
            row.getString("last_error_code"), row.getString("last_error_stage"),
            row.getString("last_error_detail"), row.getString("last_error_correlation_id"),
            rawResult == null ? null : mapper.readTree(rawResult),
            PostgresResultValues.instant(row, "created_at"),
            PostgresResultValues.instant(row, "updated_at"),
            PostgresResultValues.instant(row, "finished_at"));
    }

    public record CreateCommand(
        String providerId, Integer target, Integer maxAttempts,
        Integer concurrency, Integer attemptIntervalSeconds,
        Integer roundIntervalSeconds, Integer attemptTimeoutSeconds,
        Integer flowMaxAttempts, Integer maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy, Boolean headless, String mailDomain,
        Boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode, String idempotencyKey
    ) {
        public CreateCommand {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("provider_id is required");
            }
        }

        public CreateCommand withIdempotencyKey(String value) {
            return new CreateCommand(
                providerId, target, maxAttempts, concurrency, attemptIntervalSeconds,
                roundIntervalSeconds, attemptTimeoutSeconds, flowMaxAttempts,
                maxConsecutiveFailureBatches, proxyPolicy, headless, mailDomain,
                aiCaptchaEnabled, aiCaptchaMode, value);
        }
    }

    private static RegistrationProxyPolicy proxyPolicy(String value) {
        try { return RegistrationProxyPolicy.valueOf(value); }
        catch (IllegalArgumentException error) {
            return RegistrationProxyPolicy.PROVIDER_DEFAULT;
        }
    }
}
