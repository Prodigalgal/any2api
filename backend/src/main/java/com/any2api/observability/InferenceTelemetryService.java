package com.any2api.observability;

import com.any2api.persistence.PostgresResultValues;
import com.any2api.protocol.UsageSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class InferenceTelemetryService {
    private static final Logger log = LoggerFactory.getLogger(InferenceTelemetryService.class);
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final ExecutorService databaseExecutor;
    private final ObjectMapper mapper;

    public InferenceTelemetryService(
        JdbcClient jdbc,
        MeterRegistry meters,
        ExecutorService databaseExecutor,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.databaseExecutor = databaseExecutor;
        this.mapper = mapper;
    }

    public Started start(InferenceTrace request, int attempt) {
        return start(request, attempt, 0);
    }

    public Started start(InferenceTrace request, int attempt, long queueMs) {
        log.info(
            "inference_started correlation_id={} provider={} model={} protocol={} attempt={}",
            request.requestId(), request.providerId(), request.model(), request.protocol(), attempt);
        return new Started(request, attempt, Instant.now(), queueMs);
    }

    public final class Started {
        private final InferenceTrace request;
        private final int attempt;
        private final Instant startedAt;
        private final long startedNanos = System.nanoTime();
        private final long queueMs;
        private final AtomicReference<UUID> accountId = new AtomicReference<>();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cacheReadTokens = new AtomicLong();
        private final AtomicReference<String> errorCode = new AtomicReference<>();
        private final AtomicReference<UsageSource> usageSource =
            new AtomicReference<>(UsageSource.ESTIMATED);
        private final AtomicLong accountAcquiredNanos = new AtomicLong();
        private final AtomicLong firstByteNanos = new AtomicLong();
        private final AtomicLong terminalNanos = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final List<JsonNode> outputEvents =
            Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<JsonNode> explicitOutput = new AtomicReference<>();

        private Started(InferenceTrace request, int attempt, Instant startedAt, long queueMs) {
            this.request = request;
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.queueMs = Math.max(0, queueMs);
        }

        public void account(UUID value) { accountId.set(value); }

        public void accountAcquired() {
            accountAcquiredNanos.compareAndSet(0, System.nanoTime());
        }

        public void firstByte() {
            firstByteNanos.compareAndSet(0, System.nanoTime());
        }

        public void terminal() {
            terminalNanos.compareAndSet(0, System.nanoTime());
        }

        public void usage(long input, long output, long cacheRead, UsageSource source) {
            inputTokens.set(input);
            outputTokens.set(output);
            cacheReadTokens.set(cacheRead);
            usageSource.set(source == null ? UsageSource.ESTIMATED : source);
        }

        public void failure(String code) {
            errorCode.set(code == null || code.isBlank() ? "inference_failed" : code);
        }

        public void recordError(Throwable error) {
            errorCode.compareAndSet(null, error.getClass().getSimpleName());
        }

        public void record(Object event) {
            if (event != null) outputEvents.add(mapper.valueToTree(event));
        }

        public void output(Object value) {
            explicitOutput.set(value == null ? mapper.createObjectNode() : mapper.valueToTree(value));
        }

        public void finish(SignalType signal) {
            if (!finished.compareAndSet(false, true)) return;
            if (signal == SignalType.CANCEL && terminalNanos.get() == 0) {
                errorCode.compareAndSet(null, "downstream_cancelled");
            }
            var endedAt = Instant.now();
            var endedNanos = System.nanoTime();
            var durationMs = queueMs + elapsed(startedNanos, endedNanos);
            var accountAt = accountAcquiredNanos.get();
            var firstAt = firstByteNanos.get();
            var terminalAt = terminalNanos.get();
            var accountAcquireMs = accountAt == 0 ? 0 : elapsed(startedNanos, accountAt);
            var ttfbMs = firstAt == 0 ? 0 : elapsed(
                accountAt == 0 ? startedNanos : accountAt, firstAt);
            var generationMs = firstAt == 0 ? 0 : elapsed(
                firstAt, terminalAt == 0 ? endedNanos : terminalAt);
            var error = errorCode.get();
            var success = error == null;
            var output = explicitOutput.get();
            if (output == null) {
                var eventSnapshot = mapper.createObjectNode();
                var events = eventSnapshot.putArray("events");
                synchronized (outputEvents) {
                    outputEvents.forEach(events::add);
                }
                output = eventSnapshot;
            }
            var snapshot = new Snapshot(
                accountId.get(), inputTokens.get(), outputTokens.get(), cacheReadTokens.get(),
                usageSource.get(), durationMs, queueMs, accountAcquireMs, ttfbMs,
                generationMs, error, success, output);
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
                    duration_ms, error_class, created_at, attempt, request_kind, usage_source,
                    queue_ms, account_acquire_ms, ttfb_ms, generation_ms,
                    input_snapshot, output_snapshot)
                VALUES (:id, :requestId, :apiKeyId, :providerId, :accountId, :modelId, :protocol,
                        :success, :inputTokens, :outputTokens, :cacheReadTokens,
                        :durationMs, :errorClass, :createdAt, :attempt, :requestKind, :usageSource,
                        :queueMs, :accountAcquireMs, :ttfbMs, :generationMs,
                        CAST(:inputSnapshot AS jsonb), CAST(:outputSnapshot AS jsonb))
                ON CONFLICT (request_id, attempt) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", request.requestId())
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
                .param("attempt", attempt)
                .param("requestKind", request.requestKind())
                .param("usageSource", snapshot.usageSource().name())
                .param("queueMs", snapshot.queueMs())
                .param("accountAcquireMs", snapshot.accountAcquireMs())
                .param("ttfbMs", snapshot.ttfbMs())
                .param("generationMs", snapshot.generationMs())
                .param("inputSnapshot", mapper.writeValueAsString(
                    request.input() == null ? mapper.createObjectNode() : request.input()))
                .param("outputSnapshot", mapper.writeValueAsString(snapshot.output()))
                .update();
        }

        private void recordMetricAndLog(Snapshot snapshot) {
            Timer.builder("any2api.inference.duration")
                .tag("provider", request.providerId())
                .tag("model", request.model())
                .tag("protocol", request.protocol())
                .tag("outcome", snapshot.success() ? "success" : "failure")
                .register(meters)
                .record(Duration.ofMillis(snapshot.durationMs()));
            recordStage("queue", snapshot.queueMs());
            recordStage("account_acquire", snapshot.accountAcquireMs());
            recordStage("ttfb", snapshot.ttfbMs());
            recordStage("generation", snapshot.generationMs());
            log.atLevel(snapshot.success() ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
                .log(
                    "inference_finished correlation_id={} provider={} model={} protocol={} attempt={} account_id={} status={} error_code={} duration_ms={} queue_ms={} account_acquire_ms={} ttfb_ms={} generation_ms={} input_tokens={} output_tokens={} cache_read_tokens={} usage_source={}",
                    request.requestId(), request.providerId(), request.model(),
                    request.protocol(), attempt, snapshot.accountId(),
                    snapshot.success() ? "SUCCEEDED" : "FAILED",
                    snapshot.errorCode() == null ? "" : snapshot.errorCode(),
                    snapshot.durationMs(), snapshot.queueMs(), snapshot.accountAcquireMs(),
                    snapshot.ttfbMs(), snapshot.generationMs(), snapshot.inputTokens(),
                    snapshot.outputTokens(), snapshot.cacheReadTokens(), snapshot.usageSource());
        }

        private void recordStage(String stage, long durationMs) {
            Timer.builder("any2api.inference.stage.duration")
                .tag("provider", request.providerId())
                .tag("model", request.model())
                .tag("stage", stage)
                .register(meters)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
        }
    }

    private record Snapshot(
        UUID accountId,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        UsageSource usageSource,
        long durationMs,
        long queueMs,
        long accountAcquireMs,
        long ttfbMs,
        long generationMs,
        String errorCode,
        boolean success,
        JsonNode output
    ) {}

    public record InferenceTrace(
        String requestId,
        String providerId,
        String model,
        String protocol,
        UUID apiKeyId,
        String requestKind,
        JsonNode input
    ) {
        public InferenceTrace(
            String requestId,
            String providerId,
            String model,
            String protocol,
            UUID apiKeyId
        ) {
            this(requestId, providerId, model, protocol, apiKeyId, "INFERENCE", null);
        }

        public InferenceTrace(
            String requestId,
            String providerId,
            String model,
            String protocol,
            UUID apiKeyId,
            String requestKind
        ) {
            this(requestId, providerId, model, protocol, apiKeyId, requestKind, null);
        }

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
            requestKind = requestKind == null || requestKind.isBlank()
                ? "INFERENCE" : requestKind;
        }
    }

    private static long elapsed(long fromNanos, long toNanos) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            toNanos - fromNanos));
    }
}
