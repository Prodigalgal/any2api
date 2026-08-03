package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.account.AccountManagementService;
import com.any2api.account.AccountStatus;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.observability.OperationContext;
import com.any2api.observability.OperationEventService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RegistrationJobScheduler {
    private static final int CLAIM_LIMIT = 2;
    static final Duration LEASE_TTL = Duration.ofMinutes(12);
    static final Duration LEASE_RENEW_INTERVAL = Duration.ofMinutes(4);
    static final Duration AUTOMATION_ATTEMPT_TIMEOUT = Duration.ofMinutes(35);
    static final Duration POLL_EXECUTION_TIMEOUT = Duration.ofMinutes(40);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LifecycleAutomationClient automation;
    private final AccountManagementService accounts;
    private final ProxyPoolService proxyPools;
    private final ObjectMapper mapper;
    private final OperationEventService observability;

    public RegistrationJobScheduler(
        JdbcClient jdbc,
        TransactionTemplate transactions,
        LifecycleAutomationClient automation,
        AccountManagementService accounts,
        ProxyPoolService proxyPools,
        ObjectMapper mapper,
        OperationEventService observability
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.automation = automation;
        this.accounts = accounts;
        this.proxyPools = proxyPools;
        this.mapper = mapper;
        this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${any2api.lifecycle.registration-poll-interval:5s}")
    public void poll() {
        var owner = "registration:" + UUID.randomUUID();
        var claimed = transactions.execute(ignored -> claim(owner));
        if (claimed == null || claimed.isEmpty()) return;
        Flux.fromIterable(claimed)
            .flatMap(job -> execute(job, owner).onErrorResume(error ->
                Mono.fromRunnable(() -> failLease(job, owner, error))), CLAIM_LIMIT)
            .then().block(POLL_EXECUTION_TIMEOUT);
    }

    private List<Job> claim(String owner) {
        jdbc.sql("""
            UPDATE operation_events event SET
                status = 'FAILED', stage = 'scheduler', error_code = 'lease_expired',
                error_detail = 'registration job lease expired',
                duration_ms = GREATEST(
                    0, (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - event.started_at)) * 1000)::BIGINT),
                finished_at = CURRENT_TIMESTAMP
            FROM registration_jobs job
            WHERE event.domain = 'REGISTRATION' AND event.status = 'RUNNING'
              AND event.aggregate_id = job.id::text
              AND job.status = 'RUNNING' AND job.lease_expires_at < CURRENT_TIMESTAMP
            """).update();
        jdbc.sql("""
            UPDATE registration_jobs SET
                status = CASE WHEN cancel_requested THEN 'CANCELLED'
                    WHEN attempts + 1 >= requested THEN 'FAILED' ELSE 'PENDING' END,
                attempts = attempts + 1, failure_count = failure_count + 1,
                consecutive_failure_batches = consecutive_failure_batches + 1,
                last_error_class = 'LeaseExpired',
                last_error_code = 'lease_expired', last_error_stage = 'scheduler',
                last_error_detail = 'registration job lease expired',
                last_error_correlation_id = NULL,
                next_attempt_at = CURRENT_TIMESTAMP
                    + GREATEST(round_interval_seconds, 60) * INTERVAL '1 second',
                finished_at = CASE WHEN cancel_requested OR attempts + 1 >= requested
                    THEN CURRENT_TIMESTAMP ELSE finished_at END,
                lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP
            """).update();
        return jdbc.sql("""
            WITH candidates AS (
                SELECT id FROM registration_jobs
                WHERE status = 'PENDING' AND cancel_requested = FALSE
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, created_at
                FOR UPDATE SKIP LOCKED LIMIT :limit
            )
            UPDATE registration_jobs job SET status = 'RUNNING', lease_owner = :owner,
                lease_expires_at = CURRENT_TIMESTAMP + CAST(:leaseSeconds || ' seconds' AS interval),
                updated_at = CURRENT_TIMESTAMP
            FROM candidates WHERE job.id = candidates.id
            RETURNING job.id, job.provider_id, job.target, job.requested,
                      job.concurrency, job.attempts, job.success_count, job.failure_count,
                      job.consecutive_failure_batches, job.attempt_interval_seconds,
                      job.round_interval_seconds
            """)
            .param("limit", CLAIM_LIMIT).param("owner", owner)
            .param("leaseSeconds", Long.toString(LEASE_TTL.toSeconds()))
            .query(RegistrationJobScheduler::mapJob).list();
    }

    private Mono<Void> execute(Job job, String owner) {
        var remainingTarget = job.target() - job.successCount();
        var remainingAttempts = job.maxAttempts() - job.attempts();
        var batch = Math.min(job.concurrency(), Math.min(remainingTarget, remainingAttempts));
        if (batch <= 0) {
            return Mono.fromRunnable(() -> finalizeBatch(job, owner, List.of()));
        }
        var payload = proxyPools.runtimeForProvider(
                job.providerId(), ProxyTrafficScope.REGISTRATION)
            .<Map<String, ?>>map(pool -> Map.of("proxy_pool", pool)).orElseGet(Map::of);
        var attemptInterval = Duration.ofSeconds(job.attemptIntervalSeconds());
        var operation = Flux.range(0, batch)
            .flatMap(attempt -> delayedStart(attempt, attemptInterval)
                .then(executeAttempt(job, attempt, payload)), job.concurrency())
            .collectList()
            .doOnNext(results -> finalizeBatch(job, owner, results))
            .then();
        return withLeaseRenewal(operation, job, owner);
    }

    private Mono<Attempt> executeAttempt(
        Job job,
        int batchOffset,
        Map<String, ?> payload
    ) {
        return Mono.defer(() -> {
            var context = new OperationContext(
                UUID.randomUUID().toString(), "REGISTRATION_JOB", job.id().toString(),
                job.attempts() + batchOffset + 1);
            var observed = observability.start(
                "REGISTRATION", job.providerId(), "register", context);
            return automation.execute(job.providerId(), "register", payload, context)
                .timeout(AUTOMATION_ATTEMPT_TIMEOUT)
                .map(result -> importResult(job, result))
                .doOnNext(result -> {
                    observability.linkAccount(observed, result.accountId());
                    observability.succeed(observed, "credential_imported");
                })
                .onErrorResume(error -> {
                    var failure = observability.fail(observed, error);
                    return Mono.just(Attempt.failed(error, failure, context.correlationId()));
                });
        });
    }

    private Mono<Void> delayedStart(int attempt, Duration interval) {
        if (attempt == 0 || interval.isZero()) return Mono.empty();
        return Mono.delay(interval.multipliedBy(attempt)).then();
    }

    private Mono<Void> withLeaseRenewal(Mono<Void> operation, Job job, String owner) {
        return operation.publish(shared -> Mono.when(
            shared,
            Flux.interval(LEASE_RENEW_INTERVAL)
                .takeUntilOther(shared)
                .concatMap(ignored -> Mono.fromRunnable(() -> renewLease(job, owner)))
                .then()
        ));
    }

    private void renewLease(Job job, String owner) {
        var updated = jdbc.sql("""
            UPDATE registration_jobs
            SET lease_expires_at = CURRENT_TIMESTAMP
                    + CAST(:leaseSeconds || ' seconds' AS interval),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'RUNNING' AND lease_owner = :owner
            """)
            .param("leaseSeconds", Long.toString(LEASE_TTL.toSeconds()))
            .param("id", job.id())
            .param("owner", owner)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("registration job lease was lost during execution");
        }
    }

    private Attempt importResult(Job job, JsonNode result) {
        var credential = result.path("credential");
        var externalId = result.path("external_id").asText("").trim();
        var email = result.path("email").asText("").trim();
        if (externalId.isBlank()) externalId = email;
        if (externalId.isBlank() || !credential.isObject()) {
            throw new IllegalStateException("registration result is incomplete");
        }
        var metadata = new HashMap<String, Object>();
        if (result.path("metadata").isObject()) {
            metadata.putAll(mapper.convertValue(
                result.path("metadata"), new TypeReference<Map<String, Object>>() {}));
        }
        metadata.put("registration_job_id", job.id().toString());
        var expiresAt = instant(result.path("expires_at").asText(""));
        var credentialExpiresAt = instant(result.path("credential_expires_at").asText(""));
        var admission = registrationAdmission(
            result.path("ready_for_inference").asBoolean(false));
        metadata.put("registration_worker_ready_for_inference", admission.workerClaimedReady());
        metadata.put("inference_readiness_pending", true);
        var imported = accounts.importNewAccount(new AccountManagementService.ImportCommand(
            job.providerId(), externalId, email.isBlank() ? null : email, expiresAt,
            credentialExpiresAt, metadata, null, null, null,
            admission.status(), admission.enabled(), credential, true));
        return Attempt.succeeded(imported.account().id());
    }

    static RegistrationAdmission registrationAdmission(boolean workerClaimedReady) {
        return new RegistrationAdmission(AccountStatus.PENDING, false, workerClaimedReady);
    }

    private void finalizeBatch(Job job, String owner, List<Attempt> results) {
        transactions.executeWithoutResult(ignored -> {
            var success = (int) results.stream().filter(Attempt::success).count();
            var failure = results.size() - success;
            var attempts = job.attempts() + results.size();
            var successes = job.successCount() + success;
            var failures = job.failureCount() + failure;
            var fullyFailed = failure > 0 && success == 0;
            var failureStreak = fullyFailed ? job.consecutiveFailureBatches() + 1 : 0;
            var completed = successes >= job.target() || attempts >= job.maxAttempts();
            var status = successes >= job.target() ? "SUCCEEDED"
                : attempts >= job.maxAttempts() ? (successes > 0 ? "PARTIAL" : "FAILED")
                : "PENDING";
            var ids = results.stream().filter(Attempt::success).map(Attempt::accountId)
                .map(UUID::toString).toList();
            var errorClass = results.stream().filter(item -> !item.success())
                .map(Attempt::errorClass).findFirst().orElse(null);
            var failedAttempt = results.stream().filter(item -> !item.success())
                .findFirst().orElse(null);
            var delay = nextRegistrationDelay(
                job.id(), failureStreak, fullyFailed,
                Duration.ofSeconds(job.roundIntervalSeconds()));
            var updated = jdbc.sql("""
                UPDATE registration_jobs SET status = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE :status END,
                    attempts = :attempts, success_count = :successes, failure_count = :failures,
                    consecutive_failure_batches = :failureStreak,
                    result = jsonb_set(COALESCE(result, '{}'::jsonb), '{account_ids}',
                        COALESCE(result->'account_ids', '[]'::jsonb) || CAST(:accountIds AS jsonb)),
                    last_error_class = :errorClass, next_attempt_at = :nextAttempt,
                    last_error_code = :errorCode, last_error_stage = :errorStage,
                    last_error_detail = :errorDetail,
                    last_error_correlation_id = :errorCorrelationId,
                    lease_owner = NULL, lease_expires_at = NULL,
                    finished_at = CASE WHEN cancel_requested OR :completed THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status = 'RUNNING' AND lease_owner = :owner
                """)
                .param("status", status).param("attempts", attempts)
                .param("successes", successes).param("failures", failures)
                .param("failureStreak", failureStreak)
                .param("accountIds", mapper.writeValueAsString(ids)).param("errorClass", errorClass)
                .param("errorCode", failedAttempt == null ? null : failedAttempt.errorCode())
                .param("errorStage", failedAttempt == null ? null : failedAttempt.errorStage())
                .param("errorDetail", failedAttempt == null ? null : failedAttempt.errorDetail())
                .param("errorCorrelationId",
                    failedAttempt == null ? null : failedAttempt.correlationId())
                .param("nextAttempt", PostgresResultValues.timestamp(
                    Instant.now().plus(delay))).param("completed", completed)
                .param("id", job.id()).param("owner", owner).update();
            if (updated != 1) throw new IllegalStateException("registration job lease was lost");
        });
    }

    private void failLease(Job job, String owner, Throwable error) {
        transactions.executeWithoutResult(ignored -> {
            var attempts = job.attempts() + 1;
            var failureStreak = job.consecutiveFailureBatches() + 1;
            var failure = observability.describe(error);
            jdbc.sql("""
                UPDATE registration_jobs SET
                    status = CASE WHEN cancel_requested THEN 'CANCELLED'
                        WHEN :exhausted THEN 'FAILED' ELSE 'PENDING' END,
                    attempts = :attempts, failure_count = failure_count + 1,
                    consecutive_failure_batches = :failureStreak,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_class = :errorClass, next_attempt_at = :nextAttempt,
                    last_error_code = :errorCode, last_error_stage = :errorStage,
                    last_error_detail = :errorDetail,
                    last_error_correlation_id = NULL,
                    finished_at = CASE WHEN cancel_requested OR :exhausted
                        THEN CURRENT_TIMESTAMP ELSE finished_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status = 'RUNNING' AND lease_owner = :owner
                """).param("exhausted", attempts >= job.maxAttempts())
                .param("attempts", attempts)
                .param("failureStreak", failureStreak)
                .param("errorClass", error.getClass().getSimpleName())
                .param("errorCode", failure.code())
                .param("errorStage", failure.stage())
                .param("errorDetail", failure.detail())
                .param("nextAttempt", PostgresResultValues.timestamp(
                    Instant.now().plus(nextRegistrationDelay(
                        job.id(), failureStreak, true,
                        Duration.ofSeconds(job.roundIntervalSeconds())))))
                .param("id", job.id()).param("owner", owner).update();
        });
    }

    private static Duration registrationDelay(UUID jobId, int failureStreak) {
        var seconds = Math.min(900, 15L * (1L << Math.min(6, Math.max(0, failureStreak))));
        var jitter = Math.floorMod(31L * jobId.hashCode() + failureStreak, 30_000L);
        return Duration.ofSeconds(seconds).plusMillis(jitter);
    }

    static Duration nextRegistrationDelay(UUID jobId, int failureStreak, boolean fullyFailed) {
        return nextRegistrationDelay(jobId, failureStreak, fullyFailed, Duration.ofSeconds(5));
    }

    static Duration nextRegistrationDelay(
        UUID jobId,
        int failureStreak,
        boolean fullyFailed,
        Duration roundInterval
    ) {
        if (fullyFailed) {
            var backoff = registrationDelay(jobId, failureStreak);
            return backoff.compareTo(roundInterval) >= 0 ? backoff : roundInterval;
        }
        if (roundInterval.isZero()) return Duration.ZERO;
        var jitter = Math.floorMod(31L * jobId.hashCode() + failureStreak, 10_000L);
        return roundInterval.plusMillis(jitter);
    }

    private static Instant instant(String value) {
        try { return value == null || value.isBlank() ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Job mapJob(ResultSet row, int ignored) throws SQLException {
        return new Job(row.getObject("id", UUID.class), row.getString("provider_id"),
            row.getInt("target"), row.getInt("requested"), row.getInt("concurrency"),
            row.getInt("attempts"), row.getInt("success_count"), row.getInt("failure_count"),
            row.getInt("consecutive_failure_batches"),
            row.getInt("attempt_interval_seconds"), row.getInt("round_interval_seconds"));
    }

    private record Job(
        UUID id, String providerId, int target, int maxAttempts, int concurrency,
        int attempts, int successCount, int failureCount, int consecutiveFailureBatches,
        int attemptIntervalSeconds, int roundIntervalSeconds
    ) {}

    private record Attempt(
        boolean success,
        UUID accountId,
        String errorClass,
        String errorCode,
        String errorStage,
        String errorDetail,
        String correlationId
    ) {
        static Attempt succeeded(UUID accountId) {
            return new Attempt(true, accountId, null, null, null, null, null);
        }
        static Attempt failed(
            Throwable error,
            OperationEventService.Failure failure,
            String correlationId
        ) {
            return new Attempt(
                false, null, error.getClass().getSimpleName(), failure.code(),
                failure.stage(), failure.detail(), correlationId);
        }
    }

    record RegistrationAdmission(
        AccountStatus status,
        boolean enabled,
        boolean workerClaimedReady
    ) {}
}
