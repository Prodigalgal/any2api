package com.any2api.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class LayeredJsonCacheTest {
    @Test
    void databaseResultPopulatesRedisAndThenServesTheLocalLayer() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:key")).thenReturn(Mono.empty());
        when(values.set(eq("test:key"), eq("database"), any(Duration.class)))
            .thenReturn(Mono.just(true));
        var loads = new AtomicInteger();
        var cache = new LayeredJsonCache(
            redis, "test", Duration.ofMinutes(1), Duration.ofMinutes(5), 100);

        var first = cache.get("key", () -> {
            loads.incrementAndGet();
            return Mono.just(Optional.of("database"));
        }).block();
        var second = cache.get("key", () -> {
            loads.incrementAndGet();
            return Mono.just(Optional.of("unexpected"));
        }).block();

        assertThat(first).contains("database");
        assertThat(second).contains("database");
        assertThat(loads).hasValue(1);
        verify(values, times(1)).get("test:key");
    }

    @Test
    void redisHitSkipsTheDatabaseLayer() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:key")).thenReturn(Mono.just("redis"));
        var cache = new LayeredJsonCache(
            redis, "test", Duration.ofMinutes(1), Duration.ofMinutes(5), 100);

        var result = cache.get("key", () -> Mono.just(Optional.of("database"))).block();

        assertThat(result).contains("redis");
        verify(values, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void concurrentMissesShareOneDatabaseLoad() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:key")).thenReturn(Mono.empty());
        when(values.set(eq("test:key"), eq("database"), any(Duration.class)))
            .thenReturn(Mono.just(true));
        var gate = Sinks.<Optional<String>>one();
        var loads = new AtomicInteger();
        var cache = new LayeredJsonCache(
            redis, "test", Duration.ofMinutes(1), Duration.ofMinutes(5), 100);

        var first = cache.get("key", () -> {
            loads.incrementAndGet();
            return gate.asMono();
        });
        var second = cache.get("key", () -> {
            loads.incrementAndGet();
            return gate.asMono();
        });
        var combined = Mono.zip(first, second);
        gate.tryEmitValue(Optional.of("database"));
        var result = combined.block();

        assertThat(result).isNotNull();
        assertThat(result.getT1()).contains("database");
        assertThat(result.getT2()).contains("database");
        assertThat(loads).hasValue(1);
        verify(values, times(1)).get("test:key");
    }

    @Test
    void databaseResultDoesNotWaitForTheBestEffortRedisWrite() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:key")).thenReturn(Mono.empty());
        var write = Sinks.<Boolean>one();
        when(values.set(eq("test:key"), eq("database"), any(Duration.class)))
            .thenReturn(write.asMono());
        var cache = new LayeredJsonCache(
            redis, "test", Duration.ofMinutes(1), Duration.ofMinutes(5), 100);

        var result = cache.get(
            "key", () -> Mono.just(Optional.of("database"))).block(Duration.ofSeconds(1));

        assertThat(result).contains("database");
        verify(values).set(eq("test:key"), eq("database"), any(Duration.class));
        write.tryEmitValue(true);
    }
}
