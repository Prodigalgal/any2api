package com.any2api.observability;

import com.any2api.persistence.PostgresResultValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

@Service
public final class InferenceTelemetryService {
    private static final Logger log = LoggerFactory.getLogger(InferenceTelemetryService.class);
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final ExecutorService databaseExecutor;

    public InferenceTelemetryService(
        JdbcClient jdbc,
        MeterRegistry meters,
        ExecutorService databaseExecutor
    ) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.databaseExecutor = databaseExecutor;
    }

    public Started start(InferenceTrace request, int attempt) {
        log.info(
            "inference_started correlation_id={} provider={} model={} protocol={} attempt={}",
            request.requestId(), request.providerId(), request.model(), request.protocol(), attempt);
        return new Started(request, attempt, Instant.now());
    }

    public final class Started {
        private final InferenceTrace request;
        private final int attempt;
        private final Instant startedAt;
        private final AtomicReference<UUID> accountId = new AtomicReference<>();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cacheReadTokens = new AtomicLong();
        private final AtomicReference<String> errorCode = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private Started(InferenceTrace request, int attempt, Instant startedAt) {
            this.request = request;
            this.attempt = attempt;
            this.startedAt = startedAt;
        }

        public void account(UUID value) { accountId.set(value); }

        public void usage(long input, long output, long cacheRead) {
            inputTokens.set(input);
            outputTokens.set(output);
            cacheReadTokens.set(cacheRead);
        }

        public void failure(String code) {
            errorCode.set(code == null || code.isBlank() ? "inference_failed" : code);
        }

        public void recordError(Throwable error) {
            errorCode.compareAndSet(null, error.getClass().getSimpleName());
        }

        public void finish(SignalType signal) {
            if (!finished.compareAndSet(false, true)) return;
            if (signal == SignalType.CANCEL) errorCode.compareAndSet(null, "downstream_cancelled");
            var endedAt = Instant.now();
            var durationMs = Math.max(0, Duration.between(startedAt, endedAt).toMillis());
            var error = errorCode.get();
            var success = error == null;
            var snapshot = new Snapshot(
                accountId.get(), inputTokens.get(), outputTokens.get(), cacheReadTokens.get(),
                durationMs, error, success);
            recordMetricAndLog(snapshot);
            try {
                databaseExecutor.execute(() -> {
                    try {
                        persist(snapshot);
                    } catch (RuntimeException persistenceError) {
                        log.error(
                            "inference_telemetry_persist_failed correlation_id={} error_type={}",
                            request.requestId(), persistenceError.getClass().getSimpleName());
                    }
                });
            } catch (RuntimeException schedulingError) {
                log.error(
                    "inference_telemetry_schedule_failed correlation_id={} error_type={}",
                    request.requestId(), schedulingError.getClass().getSimpleName());
            }
        }

        private void persist(Snapshot snapshot) {
            jdbc.sql("""
                INSERT INTO usage_events(
                    id, request_id, api_key_id, provider_id, account_id, model_id, protocol,
                    success, input_tokens, output_tokens, cache_read_tokens,
                    duration_ms, error_class, created_at)
                VALUES (:id, :requestId, :apiKeyId, :providerId, :accountId, :modelId, :protocol,
                        :success, :inputTokens, :outputTokens, :cacheReadTokens,
                        :durationMs, :errorClass, :createdAt)
                ON CONFLICT (request_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", request.requestId() + ":" + attempt)
                .param("apiKeyId", request.apiKeyId())
                .param("providerId", request.providerId())
                .param("accountId", snapshot.accountId())
                .param("modelId", request.model())
                .param("protocol", request.protocol())
                .param("success", snapshot.success())
                .param("inputTokens", snapshot.inputTokens())
                .param("outputTokens", snapshot.outputTokens())
                .param("cacheReadTokens", snapshot.cacheReadTokens())
                .param("durationMs", snapshot.durationMs())
                .param("errorClass", snapshot.errorCode())
                .param("createdAt", PostgresResultValues.timestamp(startedAt))
                .update();
        }

        private void recordMetricAndLog(Snapshot snapshot) {
            Timer.builder("any2api.inference.duration")
                .tag("provider", request.providerId())
                .tag("protocol", request.protocol())
                .tag("outcome", snapshot.success() ? "success" : "failure")
                .register(meters)
                .record(Duration.ofMillis(snapshot.durationMs()));
            log.atLevel(snapshot.success() ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
                .log(
                    "inference_finished correlation_id={} provider={} model={} protocol={} attempt={} account_id={} status={} error_code={} duration_ms={} input_tokens={} output_tokens={} cache_read_tokens={}",
                    request.requestId(), request.providerId(), request.model(),
                    request.protocol(), attempt, snapshot.accountId(),
                    snapshot.success() ? "SUCCEEDED" : "FAILED",
                    snapshot.errorCode() == null ? "" : snapshot.errorCode(),
                    snapshot.durationMs(), snapshot.inputTokens(), snapshot.outputTokens(),
                    snapshot.cacheReadTokens());
        }
    }

    private record Snapshot(
        UUID accountId,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long durationMs,
        String errorCode,
        boolean success
    ) {}

    public record InferenceTrace(
        String requestId,
        String providerId,
        String model,
        String protocol,
        UUID apiKeyId
    ) {
        public InferenceTrace {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("requestId is required");
            }
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("providerId is required");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model is required");
            }
            if (protocol == null || protocol.isBlank()) {
                throw new IllegalArgumentException("protocol is required");
            }
        }
    }
}
