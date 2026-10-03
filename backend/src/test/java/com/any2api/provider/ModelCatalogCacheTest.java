package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogCacheTest {
    @Test
    void concurrentReadsShareOneDecodedSnapshotAndEvictionRefreshesMetadata() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just(snapshot(1)), Mono.just(snapshot(2)));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));
        var mapper = spy(new ObjectMapper());
        var cache = new ModelCatalogCache(mock(JdbcClient.class), mapper, redis, new Any2ApiProperties());

        var reads = Flux.range(0, 8).flatMap(ignored -> cache.list(), 8).collectList().block();
        assertThat(reads).hasSize(8).allSatisfy(entries -> {
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().metadata().path("revision").asInt()).isEqualTo(1);
            assertThat(entries).isSameAs(reads.getFirst());
        });
        assertThat(cache.find("mimo", "fixture").block()).isPresent();
        verify(mapper, times(1)).readValue(anyString(), any(JavaType.class));

        cache.invalidate().block();
        var refreshed = cache.list().block();
        assertThat(refreshed).isNotSameAs(reads.getFirst());
        assertThat(refreshed.getFirst().metadata().path("revision").asInt()).isEqualTo(2);
        verify(mapper, times(2)).readValue(anyString(), any(JavaType.class));
    }

    private String snapshot(int revision) {
        var model = new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
            "id", "fixture", "providerId", "mimo", "metadata", java.util.Map.of("revision", revision),
            "capabilities", java.util.Map.of(), "randomRoles", List.of()));
        for (var field : List.of("created", "eligibleAccountCount", "availableAccountCount",
            "quotaLimitedAccountCount", "rollingRequestCount", "rollingAttemptCount", "p50Ms", "p95Ms")) {
            model.put(field, 0);
        }
        model.put("available", true);
        model.put("rollingSuccessRate", 1.0);
        return new ObjectMapper().writeValueAsString(List.of(model));
    }
}
