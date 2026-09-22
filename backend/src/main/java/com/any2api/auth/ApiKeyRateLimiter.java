package com.any2api.auth;

import com.any2api.config.Any2ApiProperties;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages per-API-key in-flight concurrency quotas and sliding window RPM limits
 * to prevent abusive clients from exhausting shared account pools.
 */
public class ApiKeyRateLimiter {

    public static final int DEFAULT_MAX_CONCURRENCY = 20;
    public static final int DEFAULT_MAX_RPM = 120;
    private static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ArrayDeque<Long>> timestamps = new ConcurrentHashMap<>();
    private final Object windowLock = new Object();

    private final int maxConcurrency;
    private final int maxRpm;

    public ApiKeyRateLimiter(Any2ApiProperties properties) {
        this(DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_RPM);
    }

    public ApiKeyRateLimiter(int maxConcurrency, int maxRpm) {
        this.maxConcurrency = maxConcurrency > 0 ? maxConcurrency : DEFAULT_MAX_CONCURRENCY;
        this.maxRpm = maxRpm > 0 ? maxRpm : DEFAULT_MAX_RPM;
    }

    public Result tryAcquire(UUID apiKeyId) {
        if (apiKeyId == null) {
            return Result.allowed();
        }

        var counter = inFlight.computeIfAbsent(apiKeyId, ignored -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > maxConcurrency) {
            counter.decrementAndGet();
            return Result.rejected("concurrency_limit_exceeded",
                "Maximum concurrent requests (" + maxConcurrency + ") exceeded for this API key");
        }

        long now = System.currentTimeMillis();
        synchronized (windowLock) {
            var deque = timestamps.computeIfAbsent(apiKeyId, ignored -> new ArrayDeque<>());
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MILLIS) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRpm) {
                counter.decrementAndGet();
                return Result.rejected("rate_limit_exceeded",
                    "Rate limit of " + maxRpm + " requests per minute exceeded for this API key");
            }
            deque.addLast(now);
        }

        return Result.allowed();
    }

    public void release(UUID apiKeyId) {
        if (apiKeyId == null) {
            return;
        }
        var counter = inFlight.get(apiKeyId);
        if (counter != null) {
            int remaining = counter.decrementAndGet();
            if (remaining <= 0) {
                counter.set(0);
            }
        }
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public int getMaxRpm() {
        return maxRpm;
    }

    public int getInFlight(UUID apiKeyId) {
        if (apiKeyId == null) {
            return 0;
        }
        var counter = inFlight.get(apiKeyId);
        return counter != null ? counter.get() : 0;
    }

    public int getRemainingRpm(UUID apiKeyId) {
        if (apiKeyId == null) {
            return maxRpm;
        }
        synchronized (windowLock) {
            var deque = timestamps.get(apiKeyId);
            if (deque == null) {
                return maxRpm;
            }
            long now = System.currentTimeMillis();
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MILLIS) {
                deque.pollFirst();
            }
            return Math.max(0, maxRpm - deque.size());
        }
    }

    public record Result(boolean permitted, String code, String message) {
        public static Result allowed() {
            return new Result(true, null, null);
        }

        public static Result rejected(String code, String message) {
            return new Result(false, code, message);
        }
    }
}
