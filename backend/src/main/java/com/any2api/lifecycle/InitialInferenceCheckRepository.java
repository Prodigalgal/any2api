package com.any2api.lifecycle;

import com.any2api.persistence.PostgresResultValues;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InitialInferenceCheckRepository {
    private final JdbcClient jdbc;

    public InitialInferenceCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Check> find(UUID accountId) {
        return jdbc.sql("""
            SELECT account_id, claim_token, status, source, model_id, error_class,
                   output, duration_ms, started_at, completed_at
            FROM account_inference_checks WHERE account_id = :accountId
            """).param("accountId", accountId).query((row, ignored) -> new Check(
                row.getObject("account_id", UUID.class), row.getObject("claim_token", UUID.class),
                row.getString("status"), row.getString("source"), row.getString("model_id"),
                row.getString("error_class"), row.getString("output"), row.getLong("duration_ms"),
                PostgresResultValues.instant(row, "started_at"),
                PostgresResultValues.instant(row, "completed_at"))).optional();
    }

    Claim claim(UUID accountId, String model) {
        var token = UUID.randomUUID();
        var inserted = jdbc.sql("""
            INSERT INTO account_inference_checks(account_id, claim_token, status, source, model_id)
            VALUES (:accountId, :token, 'RUNNING', 'INITIAL', :model)
            ON CONFLICT (account_id) DO NOTHING
            """).param("accountId", accountId).param("token", token).param("model", model).update();
        var check = find(accountId).orElseThrow(() -> new IllegalStateException(
            "initial inference check disappeared after claim"));
        return new Claim(check, inserted == 1);
    }

    Check complete(Check claimed, InferenceReadinessProbe.Result result) {
        var updated = jdbc.sql("""
            UPDATE account_inference_checks
            SET status = :status, model_id = :model, error_class = :error,
                output = :output, duration_ms = :duration, completed_at = CURRENT_TIMESTAMP
            WHERE account_id = :accountId AND claim_token = :token AND status = 'RUNNING'
            """).param("status", result.ready() ? "READY" : "FAILED")
            .param("model", result.model()).param("error", result.errorClass())
            .param("output", result.output()).param("duration", result.durationMs())
            .param("accountId", claimed.accountId()).param("token", claimed.claimToken()).update();
        if (updated != 1) throw new IllegalStateException("initial inference check claim was lost");
        return find(claimed.accountId()).orElseThrow();
    }

    void interrupted(Check claimed) {
        jdbc.sql("""
            UPDATE account_inference_checks
            SET status = 'FAILED', error_class = 'InitialVerificationInterrupted',
                completed_at = CURRENT_TIMESTAMP
            WHERE account_id = :accountId AND claim_token = :token AND status = 'RUNNING'
            """).param("accountId", claimed.accountId()).param("token", claimed.claimToken()).update();
    }

    record Claim(Check check, boolean claimed) {}

    record Check(UUID accountId, UUID claimToken, String status, String source, String model,
                 String errorClass, String output, long durationMs, Instant startedAt, Instant completedAt) {
        InferenceReadinessProbe.Result result() {
            return "READY".equals(status)
                ? InferenceReadinessProbe.Result.ready(model, output, durationMs, null)
                : InferenceReadinessProbe.Result.failed(model, "RUNNING".equals(status)
                    ? "InitialVerificationInProgress" : errorClass, output, durationMs, null);
        }
    }
}
