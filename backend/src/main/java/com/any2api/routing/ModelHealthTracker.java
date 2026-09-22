package com.any2api.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks real-time health metrics, TTFB latency, and consecutive failures
 * per provider/model pair to inform adaptive routing and intelligent backoff.
 */
@Component
public class ModelHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthTracker.class);
    private static final double EMA_ALPHA = 0.25;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final ConcurrentMap<String, Stats> modelStats = new ConcurrentHashMap<>();

    public void recordOutcome(
        String providerId,
        String modelId,
        boolean success,
        long ttfbMs,
        String errorCode
    ) {
        if (providerId == null || modelId == null) {
            return;
        }
        var key = key(providerId, modelId);
        var stats = modelStats.computeIfAbsent(key, ignored -> new Stats());
        stats.record(success, ttfbMs, errorCode);
    }

    public double healthScore(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return 0.5;
        }
        var stats = modelStats.get(key(providerId, modelId));
        if (stats == null) {
            // Unseen models get a healthy default exploration score
            return 0.85;
        }
        return stats.computeScore();
    }

    public boolean isCooling(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return false;
        }
        var stats = modelStats.get(key(providerId, modelId));
        return stats != null && stats.isCooling();
    }

    private static String key(String providerId, String modelId) {
        return providerId + ":" + modelId;
    }

    private static final class Stats {
        private final AtomicLong totalRequests = new AtomicLong();
        private final AtomicLong successfulRequests = new AtomicLong();
        private final AtomicLong consecutiveFailures = new AtomicLong();
        private final AtomicLong smoothedTtfbMs = new AtomicLong(2000); // 2s baseline
        private final AtomicReference<Instant> backoffUntil = new AtomicReference<>();

        void record(boolean success, long ttfbMs, String errorCode) {
            totalRequests.incrementAndGet();
            if (success) {
                successfulRequests.incrementAndGet();
                consecutiveFailures.set(0);
                backoffUntil.set(null);
                if (ttfbMs > 0) {
                    smoothedTtfbMs.updateAndGet(prev ->
                        (long) (EMA_ALPHA * ttfbMs + (1.0 - EMA_ALPHA) * prev));
                }
            } else {
                var failures = consecutiveFailures.incrementAndGet();
                if (failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    var multiplier = Math.min(10, 1 << (failures - CONSECUTIVE_FAILURE_THRESHOLD));
                    var backoffSeconds = Math.min(
                        MAX_BACKOFF.toSeconds(),
                        BASE_BACKOFF.toSeconds() * multiplier);
                    var backoffExpiry = Instant.now().plusSeconds(backoffSeconds);
                    backoffUntil.set(backoffExpiry);
                    log.warn(
                        "model_health_backoff triggered consecutive_failures={} backoff_seconds={} error_code={}",
                        failures, backoffSeconds, errorCode);
                }
            }
        }

        boolean isCooling() {
            var until = backoffUntil.get();
            return until != null && Instant.now().isBefore(until);
        }

        double computeScore() {
            if (isCooling()) {
                // Cooling nodes are heavily penalized to steer traffic to healthy ones
                return 0.05;
            }
            long total = totalRequests.get();
            if (total == 0) {
                return 0.85;
            }
            double successRate = (double) successfulRequests.get() / (double) total;
            long ttfb = smoothedTtfbMs.get();
            // Latency factor: 1.0 at 0ms, drops to ~0.2 at 30s
            double latencyFactor = Math.max(0.1, 1.0 - Math.min(0.9, (double) ttfb / 30000.0));
            // 70% success rate + 30% latency weight
            return Math.max(0.1, 0.7 * successRate + 0.3 * latencyFactor);
        }
    }
}
