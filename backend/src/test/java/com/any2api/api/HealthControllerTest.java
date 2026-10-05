package com.any2api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.coordination.AccountLeaseRedisClient;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Mono;

class HealthControllerTest {
    private enum Failure { CACHE, LEASE }

    @Test
    void bothRedisConnectionsCanReadAnAbsentKeyAndRemainReady() {
        var jdbc = database();
        var cache = mock(ReactiveStringRedisTemplate.class);
        var leases = mock(AccountLeaseRedisClient.class);
        when(cache.hasKey("any2api:readiness")).thenReturn(Mono.just(false));
        when(leases.checkReadiness()).thenReturn(Mono.just(false));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = new HealthController(jdbc, cache, leases, executor).ready().block();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "UP");
        }
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void readinessFailsWhenEitherRedisConnectionFails(Failure failure) {
        var jdbc = database();
        var cache = mock(ReactiveStringRedisTemplate.class);
        var leases = mock(AccountLeaseRedisClient.class);
        when(cache.hasKey("any2api:readiness")).thenReturn(failure == Failure.CACHE
            ? Mono.error(new QueryTimeoutException("fixture")) : Mono.just(false));
        when(leases.checkReadiness()).thenReturn(failure == Failure.LEASE
            ? Mono.error(new QueryTimeoutException("fixture")) : Mono.just(false));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = new HealthController(jdbc, cache, leases, executor).ready().block();
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).containsEntry("status", "DOWN");
        }
    }

    private JdbcClient database() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("SELECT 1").query(Integer.class).single()).thenReturn(1);
        return jdbc;
    }
}
