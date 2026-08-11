package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.credential.CredentialVault;
import com.any2api.provider.ProviderRegistry;
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
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class LifecycleScheduler {
    private static final int CLAIM_LIMIT = 16;
    private static final int CONCURRENCY = 4;
    private static final int MAX_ATTEMPTS = 12;
    private static final Duration LEASE_TTL = Duration.ofMinutes(5);
    private static final Duration HEALTHY_INTERVAL = Duration.ofHours(6);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final ProviderRegistry providers;
    private final ProxyPoolService proxyPools;
    private final LifecycleOperationExecutor lifecycle;
    private final InferenceReadinessProbe readiness;
    private final ObjectMapper mapper;
    private final OperationEventService observability;
    private final RuntimeSettingsService runtimeSettings;

    public LifecycleScheduler(
        JdbcClient jdbc,
        TransactionTemplate transactions,
        AccountRepository accounts,
        CredentialVault credentials,
        ProviderRegistry providers,
        ProxyPoolService proxyPools,
        LifecycleOperationExecutor lifecycle,
        InferenceReadinessProbe readiness,
        ObjectMapper mapper,
        OperationEventService observability,
        RuntimeSettingsService runtimeSettings
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.credentials = credentials;
        this.providers = providers;
        this.proxyPools = proxyPools;
        this.lifecycle = lifecycle;
        this.readiness = readiness;
        this.mapper = mapper;
        this.observability = observability;
        this.runtimeSettings = runtimeSettings;
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

    private reactor.core.publisher.Mono<Void> execute(Action action, String owner) {
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
        }).doOnNext(task -> observability.linkAccount(observed, task.account().getId()))
            .flatMap(task -> lifecycle.execute(
                action.providerId(), action.action(), task.credential(),
                task.account().getMetadata(), task.proxyPool(), context)
            .flatMap(result -> {
                var probe = !requiresReadinessProbe(
                        action.action(), task.account().getStatus(), result.healthy())
                    ? reactor.core.publisher.Mono.just(
                        InferenceReadinessProbe.Result.notRequired())
                    : readiness.probe(
                        task.account(),
                        mergedCredential(task.credential(), result.credentialPatch()),
                        task.credentialVersion(), result.credentialExpiresAt() == null
                            ? task.credentialExpiresAt() : result.credentialExpiresAt());
                return probe.flatMap(probeResult -> reactor.core.publisher.Mono.<Void>fromRunnable(() -> {
                    transactions.executeWithoutResult(ignored ->
                        complete(action, owner, task, result, probeResult));
                    if (result.healthy() && probeResult.ready()) {
                        observability.succeed(observed, probeResult.model().isBlank()
                            ? "lifecycle_completed" : "inference_probe_ready");
                    } else {
                        var code = !probeResult.ready()
                            ? probeResult.errorClass() : result.errorClass();
                        observability.fail(
                            observed,
                            code == null || code.isBlank() ? "lifecycle_unhealthy" : code,
                            !probeResult.ready() ? "inference_probe" : "lifecycle_operation",
                            "lifecycle operation did not establish inference readiness");
                    }
                }));
            }))
            .doOnError(error -> observability.fail(observed, error))
            .contextWrite(RequestCorrelation.context(context.correlationId()));
    }

    private void complete(
        Action action,
        String owner,
        AccountTask task,
        LifecycleResult result,
        InferenceReadinessProbe.Result probe
    ) {
        var completedAt = Instant.now();
        var credentialExpiresAt = result.credentialExpiresAt();
        if (credentialExpiresAt == null) credentialExpiresAt = task.credentialExpiresAt();
        if (result.credentialPatch().isObject()) {
            var merged = (tools.jackson.databind.node.ObjectNode) task.credential().deepCopy();
            merged.setAll((tools.jackson.databind.node.ObjectNode) result.credentialPatch());
            credentials.store(task.account(), action.providerId(), merged, credentialExpiresAt);
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
        var healthy = result.healthy() && probe.ready();
        var authExpired = result.authExpired();
        var inferenceCredentialRejected = result.healthy()
            && !probe.ready()
            && "credential_rejected".equals(probe.errorClass());
        if (healthy) {
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
            action.action(), result.healthy(), authExpired, inferenceCredentialRejected);
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
        var interval = healthy ? healthyInterval(
                credentialExpiresAt, completedAt,
                Duration.ofMinutes(keepalivePolicy.intervalMinutes()))
            : reauthenticationRequired ? Duration.ofMinutes(15)
            : retryDelay(action.attempts() + 1);
        var jitterWindow = healthy
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
            var attempts = action.attempts() + 1;
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
                    Instant.now().plus(retryDelay(attempts))))
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
        if (authExpired || inferenceCredentialRejected) return "reauthenticate";
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
