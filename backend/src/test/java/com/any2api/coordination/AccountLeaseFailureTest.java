package com.any2api.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"unchecked", "rawtypes"})
class AccountLeaseFailureTest {
    private enum Operation { ACQUIRE, EXCLUSIVE, RENEW, RELEASE }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void backendTimeoutAndConnectionFailuresNeverGrantALeaseOrReportSuccess(Operation operation) {
        for (var failure : new RuntimeException[] {
            new QueryTimeoutException("private Redis endpoint"),
            new DataAccessResourceFailureException("private Redis endpoint")
        }) {
            var redis = mock(AccountLeaseRedisClient.class);
            when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.error(failure));
            StepVerifier.create(invoke(operation, new AccountLeaseService(redis)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(CoordinationUnavailableException.class)
                        .hasMessage("coordination service is temporarily unavailable");
                    assertThat(error.getCause()).isSameAs(failure);
                }).verify();
        }
    }

    @Test
    void programmingErrorsRemainDistinctFromTransientResourceFailure() {
        var redis = mock(AccountLeaseRedisClient.class);
        var failure = new IllegalArgumentException("invalid script");
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.error(failure));
        StepVerifier.create(invoke(Operation.ACQUIRE, new AccountLeaseService(redis)))
            .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();
    }

    @Test
    void capacityRejectionAndSuccessfulFencingRemainIntact() {
        var redis = mock(AccountLeaseRedisClient.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(0L));
        var service = new AccountLeaseService(redis);
        StepVerifier.create(invoke(Operation.ACQUIRE, service)).expectError(AccountCapacityException.class).verify();
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(42L));
        StepVerifier.create(service.acquire("mimo", UUID.randomUUID(), 1, Duration.ofMinutes(5)))
            .assertNext(lease -> assertThat(lease.fencingToken()).isEqualTo(42L)).verifyComplete();
    }

    private Mono<?> invoke(Operation operation, AccountLeaseService service) {
        var id = UUID.randomUUID();
        var lease = new AccountLease("mimo", id, "owner", 1, Instant.now().plusSeconds(300));
        return switch (operation) {
            case ACQUIRE -> service.acquire("mimo", id, 1, Duration.ofMinutes(5));
            case EXCLUSIVE -> service.acquireExclusive("mimo", id, Duration.ofMinutes(5));
            case RENEW -> service.renew(lease, Duration.ofMinutes(5));
            case RELEASE -> service.release(lease);
        };
    }
}
