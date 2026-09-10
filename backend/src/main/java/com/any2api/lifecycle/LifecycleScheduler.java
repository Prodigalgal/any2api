package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.credential.CredentialVault;
import com.any2api.coordination.AccountCapacityException;
import com.any2api.coordination.AccountLease;
import com.any2api.coordination.AccountLeaseService;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ModelProbeService;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import com.any2api.observability.OperationContext;
import com.any2api.observability.OperationEventService;
import com.any2api.observability.RequestCorrelation;
import com.any2api.settings.RuntimeSettingsService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class LifecycleScheduler {
    private static final int CLAIM_LIMIT = 16;
    private static final int CONCURRENCY = 4;
    private static final int MAX_ATTEMPTS = 12;
    private static final Duration LEASE_TTL = Duration.ofMinutes(5);
    private static final Duration HEALTHY_INTERVAL = Duration.ofHours(6);
    private static final Duration EXHAUSTED_REARM_COOLDOWN = Duration.ofMinutes(30);
    private static final Duration EXHAUSTED_REARM_DELAY = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleScheduler.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final AccountLeaseService accountLeases;
    private final ProviderRegistry providers;
    private final ProxyPoolService proxyPools;
    private final LifecycleOperationExecutor lifecycle;
    private final InferenceReadinessProbe readiness;
    private final ObjectMapper mapper;
    private final OperationEventService observability;
    private final RuntimeSettingsService runtimeSettings;
    private final List<CredentialPropagationPolicy> credentialPropagationPolicies;
    private final ModelProbeService modelProbes;

    public LifecycleScheduler(
        JdbcClient jdbc,
        TransactionTemplate transactions,
        AccountRepository accounts,
        CredentialVault credentials,
        AccountLeaseService accountLeases,
        ProviderRegistry providers,
        ProxyPoolService proxyPools,
        LifecycleOperationExecutor lifecycle,
        InferenceReadinessProbe readiness,
        ObjectMapper mapper,
        OperationEventService observability,
        RuntimeSettingsService runtimeSettings,
        List<CredentialPropagationPolicy> credentialPropagationPolicies,
        ModelProbeService modelProbes
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.credentials = credentials;
        this.accountLeases = accountLeases;
        this.providers = providers;
        this.proxyPools = proxyPools;
        this.lifecycle = lifecycle;
        this.readiness = readiness;
        this.mapper = mapper;
        this.observability = observability;
        this.runtimeSettings = runtimeSettings;
        this.credentialPropagationPolicies = List.copyOf(credentialPropagationPolicies);
        this.modelProbes = modelProbes;
    }

    @Scheduled(fixedDelayString = "${any2api.lifecycle.poll-interval:10s}")
    public void poll() {
        var owner = "scheduler:" + UUID.randomUUID();
        var claimed = transactions.execute(status -> claim(owner));
        if (claimed == null || claimed.isEmpty()) return;
        Flux.fromIterable(claimed)
            .flatMap(action -> execute(action, owner)
                .onErrorResume(error -> fail(action, owner, error).then()), CONCURRENCY)
            .then()
            .block(Duration.ofMinutes(10));
    }

    private List<Action> claim(String owner) {
        var rearmed = reactivateExhaustedActions();
        if (rearmed > 0) {
            LOGGER.info("lifecycle_exhausted_actions_rearmed count={}", rearmed);
        }
        jdbc.sql("""
            UPDATE operation_events event SET
                status = 'FAILED', stage = 'scheduler', error_code = 'action_expired',
                error_detail = 'lifecycle action expired',
                duration_ms = GREATEST(
                    0, (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - event.started_at)) * 1000)::BIGINT),
                finished_at = CURRENT_TIMESTAMP
            FROM scheduled_actions action
            WHERE event.domain = 'LIFECYCLE' AND event.status = 'RUNNING'
              AND event.aggregate_id = action.entity_id
              AND event.operation = action.action_family
              AND action.status = 'LEASED'
              AND action.expires_at IS NOT NULL AND action.expires_at <= CURRENT_TIMESTAMP
            """).update();
        jdbc.sql("""
            UPDATE operation_events event SET
                status = 'FAILED', stage = 'scheduler', error_code = 'lease_expired',
                error_detail = 'lifecycle action lease expired',
                duration_ms = GREATEST(
                    0, (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - event.started_at)) * 1000)::BIGINT),
                finished_at = CURRENT_TIMESTAMP
            FROM scheduled_actions action
            WHERE event.domain = 'LIFECYCLE' AND event.status = 'RUNNING'
              AND event.aggregate_id = action.entity_id
              AND event.operation = action.action_family
              AND action.status = 'LEASED' AND action.lease_expires_at < CURRENT_TIMESTAMP
            """).update();
        jdbc.sql("""
            UPDATE scheduled_actions
            SET status = 'EXPIRED', lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'ActionExpired', updated_at = CURRENT_TIMESTAMP
            WHERE status IN ('PENDING', 'LEASED')
              AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
            """).update();
        jdbc.sql("""
            UPDATE scheduled_actions
            SET status = CASE WHEN attempts + 1 >= :maxAttempts
                    THEN 'EXHAUSTED' ELSE 'PENDING' END,
                attempts = attempts + 1,
                due_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = 'LeaseExpired', updated_at = CURRENT_TIMESTAMP
            WHERE status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            """).param("maxAttempts", MAX_ATTEMPTS).update();
        return jdbc.sql("""
            WITH candidates AS (
                SELECT id FROM scheduled_actions
                WHERE status = 'PENDING'
                  AND due_at <= CURRENT_TIMESTAMP
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                ORDER BY priority DESC, due_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            UPDATE scheduled_actions action
            SET status = 'LEASED', lease_owner = :owner,
                lease_expires_at = CURRENT_TIMESTAMP + CAST(:leaseSeconds || ' seconds' AS interval),
                updated_at = CURRENT_TIMESTAMP
            FROM candidates
            WHERE action.id = candidates.id
            RETURNING action.id, action.provider_id, action.entity_id,
                      action.action_family, action.generation, action.attempts
            """)
            .param("limit", CLAIM_LIMIT)
            .param("owner", owner)
            .param("leaseSeconds", Long.toString(LEASE_TTL.toSeconds()))
            .query(LifecycleScheduler::mapAction)
            .list();
    }

    private int reactivateExhaustedActions() {
        return jdbc.sql("""
            WITH eligible AS (
                SELECT action.id,
                       COALESCE(MAX(history.generation), 0) + 1 AS next_generation
                FROM scheduled_actions action
                JOIN accounts account ON account.id::text = action.entity_id
                LEFT JOIN scheduled_actions history
                  ON history.provider_id = action.provider_id
                 AND history.entity_type = action.entity_type
                 AND history.entity_id = action.entity_id
                WHERE action.status = 'EXHAUSTED'
                  AND action.entity_type = 'ACCOUNT'
                  AND action.action_family IN ('keepalive', 'reauthenticate', 'daily_checkin')
                  AND account.status = 'ACTIVE'
                  AND account.enabled = TRUE
                  AND (action.expires_at IS NULL OR action.expires_at > CURRENT_TIMESTAMP)
                  AND action.updated_at <= CURRENT_TIMESTAMP
                      - CAST(:rearmCooldownSeconds || ' seconds' AS interval)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM scheduled_actions active
                      WHERE active.provider_id = action.provider_id
                        AND active.entity_type = action.entity_type
                        AND active.entity_id = action.entity_id
                        AND active.status IN ('PENDING', 'LEASED')
                  )
                GROUP BY action.id
            )
            UPDATE scheduled_actions action
            SET status = 'PENDING',
                generation = eligible.next_generation,
                attempts = 0,
                due_at = CURRENT_TIMESTAMP + CAST(:rearmDelaySeconds || ' seconds' AS interval)
                    + (MOD(ABS(hashtext(action.entity_id)::bigint), :jitterSeconds)
                        * INTERVAL '1 second'),
                idempotency_key = 'account:' || action.entity_id || ':'
                    || action.action_family || ':' || eligible.next_generation,
                lease_owner = NULL,
                lease_expires_at = NULL,
                last_error_class = 'ExhaustedActionRearmed',
                updated_at = CURRENT_TIMESTAMP
            FROM eligible
            WHERE action.id = eligible.id
            """)
            .param("rearmCooldownSeconds", EXHAUSTED_REARM_COOLDOWN.toSeconds())
            .param("rearmDelaySeconds", EXHAUSTED_REARM_DELAY.toSeconds())
            .param("jitterSeconds", 900)
            .update();
    }

    private reactor.core.publisher.Mono<Void> execute(Action action, String owner) {
        return Mono.usingWhen(
            accountLeases.acquireExclusive(
                action.providerId(), UUID.fromString(action.entityId()), LEASE_TTL),
            lease -> Mono.firstWithSignal(
                renewOwnership(action, owner, lease).then(executeOwned(action, owner, lease)),
                Flux.interval(Duration.ofMinutes(1))
                    .concatMap(ignored -> renewOwnership(action, owner, lease))
                    .then()),
            lease -> accountLeases.release(lease).then(),
            (lease, error) -> accountLeases.release(lease).then(),
            lease -> accountLeases.release(lease).then());
    }

    private Mono<Void> renewOwnership(Action action, String owner, AccountLease lease) {
        return accountLeases.renew(lease, LEASE_TTL)
            .flatMap(renewed -> !renewed
                ? Mono.<Void>error(new IllegalStateException("lifecycle account lease was lost"))
                : Mono.<Void>fromRunnable(() -> {
                    var updated = jdbc.sql("""
                        UPDATE scheduled_actions
                        SET lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                        WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
                          AND lease_expires_at > CURRENT_TIMESTAMP
                        """).param("id", action.id()).param("owner", owner).update();
                    if (updated != 1) {
                        throw new IllegalStateException("lifecycle action lease was lost");
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Void> executeOwned(Action action, String owner, AccountLease lease) {
        var context = new OperationContext(
            UUID.randomUUID().toString(), "ACCOUNT", action.entityId(), action.attempts() + 1);
        var observed = observability.start(
            "LIFECYCLE", action.providerId(), action.action(), context);
        return reactor.core.publisher.Mono.fromCallable(() -> {
            providers.require(action.providerId());
            var accountId = UUID.fromString(action.entityId());
            var account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("unknown account: " + accountId));
            if (!account.getProviderId().equals(action.providerId())) {
                throw new IllegalStateException("scheduled account/provider ownership mismatch");
            }
            var credential = credentials.read(account, action.providerId());
            return new AccountTask(
                account,
                credential.payload(),
                credential.version(),
                credential.expiresAt(),
                proxyPools.runtimeForProvider(
                    action.providerId(), ProxyTrafficScope.LIFECYCLE).orElse(null));
        }).subscribeOn(Schedulers.boundedElastic())
            .doOnNext(task -> observability.linkAccount(observed, task.account().getId()))
            .flatMap(task -> lifecycle.execute(
                action.providerId(), action.action(), task.credential(),
                task.account().getMetadata(), task.proxyPool(), context)
            .flatMap(result -> {
                var dailyCheckinSupported = supportsDailyCheckin(action.providerId());
                var readinessRequired = requiresReadinessProbe(
                        action.action(), task.account().getStatus(), result.healthy(),
                        dailyCheckinSupported);
                var probe = !readinessRequired
                    ? reactor.core.publisher.Mono.just(
                        InferenceReadinessProbe.Result.notRequired())
                    : readiness.probe(
                        task.account(),
                        mergedCredential(task.credential(), result.credentialPatch()),
                        task.credentialVersion(), result.credentialExpiresAt() == null
                            ? task.credentialExpiresAt() : result.credentialExpiresAt());
                return probe.flatMap(probeResult -> renewOwnership(action, owner, lease)
                    .then(reactor.core.publisher.Mono.<Void>fromRunnable(() -> {
                    transactions.executeWithoutResult(ignored ->
                        complete(action, owner, task, result, probeResult,
                            readinessRequired, dailyCheckinSupported));
                    var inferenceReady = result.healthy() && probeResult.ready()
                        && (readinessRequired
                            || (task.account().getStatus() == AccountStatus.ACTIVE
                                && task.account().isEnabled()));
                    if (inferenceReady) {
                        observability.succeed(observed, probeResult.model().isBlank()
                            ? "lifecycle_completed" : "inference_probe_ready");
                    } else if (result.healthy() && probeResult.ready()) {
                        observability.succeed(observed, "daily_checkin_completed");
                    } else {
                        var code = !probeResult.ready()
                            ? probeResult.errorClass() : result.errorClass();
                        observability.fail(
                            observed,
                            code == null || code.isBlank() ? "lifecycle_unhealthy" : code,
                            !probeResult.ready() ? "inference_probe" : "lifecycle_operation",
                            readinessFailureDetail(probeResult, result));
                    }
                }).subscribeOn(Schedulers.boundedElastic())));
            }))
            .doOnError(error -> observability.fail(observed, error))
            .contextWrite(RequestCorrelation.context(context.correlationId()));
    }

    private void complete(
        Action action,
        String owner,
        AccountTask task,
        LifecycleResult result,
        InferenceReadinessProbe.Result probe,
        boolean readinessRequired,
        boolean dailyCheckinSupported
    ) {
        var completedAt = Instant.now();
        var credentialExpiresAt = result.credentialExpiresAt();
        if (credentialExpiresAt == null) credentialExpiresAt = task.credentialExpiresAt();
        var recoveredCredential = mergedCredential(
            mergedCredential(task.credential(), result.credentialPatch()), probe.credentialPatch());
        credentials.storeIfVersion(
            task.account(), action.providerId(), task.credentialVersion(),
            recoveredCredential, credentialExpiresAt);
        if (credentialExpiresAt != null
            && !credentialExpiresAt.equals(task.account().getExpiresAt())) {
            task.account().updateCredentialExpiry(credentialExpiresAt);
            accounts.save(task.account());
        }
        if (result.metadataPatch().isObject()) {
            task.account().mergeMetadata(mapper.convertValue(
                result.metadataPatch(), new TypeReference<Map<String, Object>>() {}));
            accounts.save(task.account());
        }
        if (!probe.model().isBlank()) {
            task.account().mergeMetadata(Map.of(
                "inference_probe_at", completedAt.toString(),
                "inference_probe_model", probe.model(),
                "inference_probe_status", probe.ready() ? "READY" : "FAILED",
                "inference_probe_error", probe.errorClass(),
                "inference_readiness_pending", !probe.ready()));
            accounts.save(task.account());
        }
        if (result.healthy() && probe.ready() && !probe.model().isBlank()) {
            modelProbes.recordReadyEvidence(
                action.providerId(), probe.model(), task.account().getId(),
                probe.durationMs(), completedAt);
        }
        if (result.healthy() && "reauthenticate".equals(action.action())) {
            for (var policy : credentialPropagationPolicies) {
                policy.propagate(task.account(), recoveredCredential, credentialExpiresAt);
            }
        }
        var healthy = result.healthy() && probe.ready();
        var inferenceReady = probe.ready() && (readinessRequired
            || (task.account().getStatus() == AccountStatus.ACTIVE && task.account().isEnabled()));
        var authExpired = result.authExpired();
        var inferenceCredentialRejected = result.healthy() && readinessRequired
            && !probe.ready()
            && "credential_rejected".equals(probe.errorClass());
        if (healthy && inferenceReady) {
            if (task.account().getStatus() != AccountStatus.ACTIVE) {
                task.account().updateState(AccountStatus.ACTIVE, true);
                accounts.save(task.account());
            }
            accounts.markSuccess(task.account().getId(), completedAt);
        } else if (result.healthy() && !probe.ready()) {
            accounts.markReadinessFailure(
                task.account().getId(), completedAt,
                "InferenceProbe:" + probe.errorClass(),
                completedAt.plus(retryDelay(action.attempts() + 1)));
        } else if (authExpired) {
            task.account().updateState(AccountStatus.EXPIRED, true);
            accounts.save(task.account());
        }
        if (result.terminal()) {
            exhaust(action, owner, "TerminalAuthenticationFailure");
            return;
        }
        var nextAttempts = healthy ? 0 : action.attempts() + 1;
        if (!healthy && nextAttempts >= MAX_ATTEMPTS) {
            exhaust(action, owner, "LifecycleAttemptsExhausted");
            return;
        }
        var nextGeneration = action.generation() + 1;
        var nextAction = nextAction(
            action.action(), result.healthy(), authExpired, inferenceCredentialRejected,
            task.account().getStatus(), task.account().isEnabled(), dailyCheckinSupported);
        if (result.healthy() || authExpired) {
            jdbc.sql("""
                UPDATE scheduled_actions SET status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
                WHERE id <> :id AND provider_id = :providerId AND entity_type = 'ACCOUNT'
                  AND entity_id = :entityId AND action_family = :nextAction
                  AND status IN ('PENDING', 'LEASED')
                """).param("id", action.id()).param("providerId", action.providerId())
                .param("entityId", task.account().getId().toString()).param("nextAction", nextAction)
                .update();
        }
        var reauthenticationRequired = authExpired || inferenceCredentialRejected;
        var keepalivePolicy = runtimeSettings.keepalivePolicy(action.providerId());
        var activationFollowup = dailyCheckinSupported && result.healthy()
            && ("reauthenticate".equals(action.action())
                || ("daily_checkin".equals(action.action())
                    && (task.account().getStatus() != AccountStatus.ACTIVE
                        || !task.account().isEnabled())));
        var nextDailyCheckin = dailyCheckinSupported && "daily_checkin".equals(nextAction);
        var interval = activationFollowup ? Duration.ofMinutes(1)
            : healthy && nextDailyCheckin ? Duration.ofHours(24)
            : healthy ? healthyInterval(
                credentialExpiresAt, completedAt,
                Duration.ofMinutes(keepalivePolicy.intervalMinutes()))
            : reauthenticationRequired ? Duration.ofMinutes(15)
            : retryDelay(action.attempts() + 1);
        var jitterWindow = activationFollowup ? Duration.ZERO
            : healthy && nextDailyCheckin ? Duration.ofMinutes(30)
            : healthy
            ? Duration.ofMinutes(keepalivePolicy.jitterMinutes()) : Duration.ofMinutes(20);
        var dueAt = completedAt.plus(interval).plus(LifecycleScheduleService.deterministicJitter(
            task.account().getId(), nextGeneration, jitterWindow));
        var updated = jdbc.sql("""
            UPDATE scheduled_actions
            SET status = 'PENDING', generation = :generation, attempts = :attempts,
                action_family = :nextAction, due_at = :dueAt, idempotency_key = :idempotencyKey,
                lease_owner = NULL, lease_expires_at = NULL, last_error_class = :errorClass,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
            """)
            .param("generation", nextGeneration)
            .param("attempts", nextAttempts)
            .param("nextAction", nextAction)
            .param("dueAt", PostgresResultValues.timestamp(dueAt))
            .param("idempotencyKey", "action:" + action.id() + ":" + nextGeneration)
            .param("errorClass", healthy ? null
                : !probe.ready() ? probe.errorClass() : result.errorClass())
            .param("id", action.id())
            .param("owner", owner)
            .update();
        if (updated != 1) throw new IllegalStateException("lifecycle lease was lost before completion");
    }

    private reactor.core.publisher.Mono<Void> fail(Action action, String owner, Throwable error) {
        return reactor.core.publisher.Mono.fromRunnable(() -> transactions.executeWithoutResult(ignored -> {
            var busy = error instanceof AccountCapacityException;
            var attempts = busy ? action.attempts() : action.attempts() + 1;
            jdbc.sql("""
                UPDATE scheduled_actions
                SET status = CASE WHEN :exhausted THEN 'EXHAUSTED' ELSE 'PENDING' END,
                    attempts = :attempts, due_at = :dueAt,
                    lease_owner = NULL, lease_expires_at = NULL, last_error_class = :errorClass,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
                """)
                .param("attempts", attempts)
                .param("exhausted", attempts >= MAX_ATTEMPTS)
                .param("dueAt", PostgresResultValues.timestamp(
                    Instant.now().plus(busy ? Duration.ofMinutes(1) : retryDelay(attempts))))
                .param("errorClass", error.getClass().getSimpleName())
                .param("id", action.id())
                .param("owner", owner)
                .update();
        }));
    }

    private void exhaust(Action action, String owner, String errorClass) {
        var updated = jdbc.sql("""
            UPDATE scheduled_actions
            SET status = 'EXHAUSTED', lease_owner = NULL, lease_expires_at = NULL,
                last_error_class = :errorClass, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
            """).param("errorClass", errorClass).param("id", action.id())
            .param("owner", owner).update();
        if (updated != 1) throw new IllegalStateException("lifecycle lease was lost before completion");
    }

    private static Duration retryDelay(int attempts) {
        var exponent = Math.min(10, Math.max(0, attempts));
        return Duration.ofSeconds(Math.min(21_600, 30L * (1L << exponent)));
    }

    static String readinessFailureDetail(
        InferenceReadinessProbe.Result probe,
        LifecycleResult result
    ) {
        if (!probe.ready()) {
            return "inference readiness probe failed model="
                + boundedLabel(probe.model()) + " error=" + boundedLabel(probe.errorClass());
        }
        return "lifecycle operation reported unhealthy error="
            + boundedLabel(result.errorClass());
    }

    private static String boundedLabel(String value) {
        var normalized = value == null ? "" : value
            .replaceAll("[\\p{Cntrl}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.isBlank()) return "unknown";
        return normalized.substring(0, Math.min(120, normalized.length()));
    }

    private boolean supportsDailyCheckin(String providerId) {
        return providers.require(providerId).manifest().capabilities().getOrDefault(
            ProviderCapability.ACCOUNT_DAILY_CHECKIN, SupportLevel.UNSUPPORTED)
            != SupportLevel.UNSUPPORTED;
    }

    private static tools.jackson.databind.JsonNode mergedCredential(
        tools.jackson.databind.JsonNode credential,
        tools.jackson.databind.JsonNode patch
    ) {
        if (!patch.isObject()) return credential;
        var merged = (tools.jackson.databind.node.ObjectNode) credential.deepCopy();
        merged.setAll((tools.jackson.databind.node.ObjectNode) patch);
        return merged;
    }

    static Duration healthyInterval(Instant credentialExpiresAt, Instant now) {
        return healthyInterval(credentialExpiresAt, now, HEALTHY_INTERVAL);
    }

    static Duration healthyInterval(
        Instant credentialExpiresAt,
        Instant now,
        Duration maximumInterval
    ) {
        if (credentialExpiresAt == null) return maximumInterval;
        var untilRefreshWindow = Duration.between(
            now, credentialExpiresAt.minus(Duration.ofMinutes(20)));
        if (untilRefreshWindow.compareTo(Duration.ofMinutes(5)) < 0) {
            return Duration.ofMinutes(5);
        }
        return untilRefreshWindow.compareTo(maximumInterval) > 0
            ? maximumInterval : untilRefreshWindow;
    }

    static boolean requiresReadinessProbe(
        String action,
        AccountStatus status,
        boolean operationHealthy
    ) {
        return requiresReadinessProbe(action, status, operationHealthy, false);
    }

    static boolean requiresReadinessProbe(
        String action,
        AccountStatus status,
        boolean operationHealthy,
        boolean dailyCheckinSupported
    ) {
        if (dailyCheckinSupported && "daily_checkin".equals(action)) return false;
        return operationHealthy && (status == AccountStatus.PENDING
            || status == AccountStatus.EXPIRED
            || "reauthenticate".equals(action));
    }

    static String nextAction(
        String current,
        boolean operationHealthy,
        boolean authExpired,
        boolean inferenceCredentialRejected
    ) {
        return nextAction(
            current, operationHealthy, authExpired, inferenceCredentialRejected,
            AccountStatus.ACTIVE, true, false);
    }

    static String nextAction(
        String current,
        boolean operationHealthy,
        boolean authExpired,
        boolean inferenceCredentialRejected,
        AccountStatus status,
        boolean enabled,
        boolean dailyCheckinSupported
    ) {
        if (authExpired || inferenceCredentialRejected) return "reauthenticate";
        if (dailyCheckinSupported && operationHealthy
            && "reauthenticate".equals(current)) return "daily_checkin";
        if (dailyCheckinSupported && operationHealthy
            && "daily_checkin".equals(current)
            && (status != AccountStatus.ACTIVE || !enabled)) return "keepalive";
        if (dailyCheckinSupported && operationHealthy && "keepalive".equals(current)) {
            return "daily_checkin";
        }
        if (operationHealthy && "reauthenticate".equals(current)) return "keepalive";
        return current;
    }

    private static Action mapAction(ResultSet row, int ignored) throws SQLException {
        return new Action(
            row.getObject("id", UUID.class), row.getString("provider_id"),
            row.getString("entity_id"), row.getString("action_family"),
            row.getLong("generation"), row.getInt("attempts"));
    }

    private record Action(
        UUID id, String providerId, String entityId, String action, long generation, int attempts
    ) {}

    private record AccountTask(
        com.any2api.account.AccountEntity account,
        tools.jackson.databind.JsonNode credential,
        long credentialVersion,
        Instant credentialExpiresAt,
        Map<String, Object> proxyPool
    ) {}
}
