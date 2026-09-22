package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyRateLimiterTest {

    @Test
    void allowsRequestsWithinLimits() {
        var limiter = new ApiKeyRateLimiter(5, 10);
        var keyId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            var res = limiter.tryAcquire(keyId);
            assertThat(res.permitted()).isTrue();
        }

        assertThat(limiter.getInFlight(keyId)).isEqualTo(5);
        limiter.release(keyId);
        assertThat(limiter.getInFlight(keyId)).isEqualTo(4);
    }

    @Test
    void rejectsWhenConcurrencyExceeded() {
        var limiter = new ApiKeyRateLimiter(2, 100);
        var keyId = UUID.randomUUID();

        assertThat(limiter.tryAcquire(keyId).permitted()).isTrue();
        assertThat(limiter.tryAcquire(keyId).permitted()).isTrue();

        var blocked = limiter.tryAcquire(keyId);
        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.code()).isEqualTo("concurrency_limit_exceeded");

        limiter.release(keyId);
        assertThat(limiter.tryAcquire(keyId).permitted()).isTrue();
    }

    @Test
    void rejectsWhenRpmExceeded() {
        var limiter = new ApiKeyRateLimiter(10, 3);
        var keyId = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(keyId).permitted()).isTrue();
            limiter.release(keyId);
        }

        var blocked = limiter.tryAcquire(keyId);
        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.code()).isEqualTo("rate_limit_exceeded");
    }

    @Test
    void nullKeyIdIsAlwaysAllowed() {
        var limiter = new ApiKeyRateLimiter(1, 1);
        assertThat(limiter.tryAcquire(null).permitted()).isTrue();
        limiter.release(null);
        assertThat(limiter.tryAcquire(null).permitted()).isTrue();
    }
}
