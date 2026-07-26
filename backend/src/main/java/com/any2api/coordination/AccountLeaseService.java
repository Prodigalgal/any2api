package com.any2api.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AccountLeaseService {

    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
        local time = redis.call('TIME')
        local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
        local ttl = tonumber(ARGV[1])
        local capacity = tonumber(ARGV[2])
        local owner = ARGV[3]
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
        if redis.call('ZCARD', KEYS[1]) >= capacity then
          return 0
        end
        local fence = redis.call('INCR', KEYS[2])
        redis.call('ZADD', KEYS[1], now + ttl, owner)
        redis.call('PEXPIRE', KEYS[1], ttl + 60000)
        redis.call('PEXPIRE', KEYS[2], 86400000)
        return fence
        """, Long.class);

    private static final RedisScript<Long> RENEW = RedisScript.of("""
        local time = redis.call('TIME')
        local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
        local owner = ARGV[1]
        local ttl = tonumber(ARGV[2])
        if redis.call('ZSCORE', KEYS[1], owner) == false then
          return 0
        end
        redis.call('ZADD', KEYS[1], now + ttl, owner)
        redis.call('PEXPIRE', KEYS[1], ttl + 60000)
        return 1
        """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
        return redis.call('ZREM', KEYS[1], ARGV[1])
        """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public AccountLeaseService(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<AccountLease> acquire(
        String providerId,
        UUID accountId,
        int maxConcurrency,
        Duration ttl
    ) {
        if (maxConcurrency < 1 || ttl.isNegative() || ttl.isZero()) {
            return Mono.error(new IllegalArgumentException("positive capacity and lease TTL are required"));
        }
        var owner = UUID.randomUUID().toString();
        var keys = keys(providerId, accountId);
        return redis.execute(ACQUIRE, keys, List.of(
                Long.toString(ttl.toMillis()),
                Integer.toString(maxConcurrency),
                owner))
            .singleOrEmpty()
            .switchIfEmpty(Mono.error(new IllegalStateException("coordination unavailable")))
            .flatMap(fence -> fence == 0
                ? Mono.error(new AccountCapacityException(providerId, accountId))
                : Mono.just(new AccountLease(
                    providerId,
                    accountId,
                    owner,
                    fence,
                    Instant.now().plus(ttl))));
    }

    public Mono<Boolean> renew(AccountLease lease, Duration ttl) {
        return redis.execute(RENEW, List.of(leaseKey(lease.providerId(), lease.accountId())), List.of(
                lease.ownerToken(),
                Long.toString(ttl.toMillis())))
            .singleOrEmpty()
            .map(result -> result == 1)
            .defaultIfEmpty(false);
    }

    public Mono<Boolean> release(AccountLease lease) {
        return redis.execute(RELEASE, List.of(leaseKey(lease.providerId(), lease.accountId())), List.of(
                lease.ownerToken()))
            .singleOrEmpty()
            .map(result -> result == 1)
            .defaultIfEmpty(false);
    }

    private List<String> keys(String providerId, UUID accountId) {
        var tag = "{" + providerId + ":" + accountId + "}";
        return List.of("any2api:account:" + tag + ":leases", "any2api:account:" + tag + ":fence");
    }

    private String leaseKey(String providerId, UUID accountId) {
        return keys(providerId, accountId).getFirst();
    }
}

