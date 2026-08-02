package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.config.SecuritySettingsValidatorTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

class LoginChallengeServiceTest {
    @Test
    void verifiesMathAndPowOnceAndRejectsReplay() throws Exception {
        var stored = new ConcurrentHashMap<String, String>();
        var redis = redis(stored);
        var properties = SecuritySettingsValidatorTest.configured();
        properties.getSecurity().setLoginPowDifficulty(12);
        var service = new LoginChallengeService(redis, properties);

        var challenge = service.issue().block();
        assertThat(challenge).isNotNull();
        assertThat(Duration.between(Instant.now(), challenge.expiresAt()))
            .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
        var answer = answer(challenge.expression());
        var nonce = solve(challenge.challengeToken(), challenge.difficulty());

        assertThat(service.verify(challenge.challengeToken(), answer, nonce).block()).isTrue();
        assertThat(service.verify(challenge.challengeToken(), answer, nonce).block()).isFalse();
        assertThat(stored).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ReactiveStringRedisTemplate redis(Map<String, String> stored) {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var values = (ReactiveValueOperations<String, String>) mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(true);
        });
        when(values.getAndDelete(anyString())).thenAnswer(invocation ->
            Mono.justOrEmpty(stored.remove(invocation.getArgument(0))));
        return redis;
    }

    private String answer(String expression) {
        var fields = expression.split(" ");
        var left = Integer.parseInt(fields[0]);
        var right = Integer.parseInt(fields[2]);
        return Integer.toString("x".equals(fields[1]) ? left * right : left + right);
    }

    private long solve(String token, int difficulty) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (long nonce = 0; ; nonce++) {
            var hash = digest.digest((token + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            if (hasLeadingZeroBits(hash, difficulty)) return nonce;
        }
    }

    private boolean hasLeadingZeroBits(byte[] hash, int bits) {
        for (var index = 0; index < bits / 8; index++) {
            if (hash[index] != 0) return false;
        }
        var remaining = bits % 8;
        return remaining == 0 || (hash[bits / 8] & (0xff << (8 - remaining))) == 0;
    }
}
