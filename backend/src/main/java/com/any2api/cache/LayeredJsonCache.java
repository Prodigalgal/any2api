package com.any2api.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

public final class LayeredJsonCache {
    private static final Logger logger = LoggerFactory.getLogger(LayeredJsonCache.class);
    private final Cache<String, String> local;
    private final ReactiveStringRedisTemplate redis;
    private final String namespace;
    private final Duration redisTtl;
    private final ConcurrentHashMap<String, Mono<Optional<String>>> inFlight =
        new ConcurrentHashMap<>();

    public LayeredJsonCache(
        ReactiveStringRedisTemplate redis,
        String namespace,
        Duration localTtl,
        Duration redisTtl,
        long maximumSize
    ) {
        this.redis = redis;
        this.namespace = namespace.endsWith(":") ? namespace : namespace + ":";
        this.redisTtl = redisTtl;
        this.local = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(localTtl)
            .build();
    }

    public Mono<Optional<String>> get(
        String key,
        Supplier<Mono<Optional<String>>> databaseLoader
    ) {
        var localValue = local.getIfPresent(key);
        if (localValue != null) return Mono.just(Optional.of(localValue));
        var active = inFlight.get(key);
        if (active != null) return active;
        var created = load(key, databaseLoader)
            .doFinally(ignored -> inFlight.remove(key))
            .cache();
        var raced = inFlight.putIfAbsent(key, created);
        if (raced != null) return raced;
        return created;
    }

    private Mono<Optional<String>> load(
        String key,
        Supplier<Mono<Optional<String>>> databaseLoader
    ) {
        var database = Mono.defer(databaseLoader).flatMap(value -> {
            if (value.isEmpty()) return Mono.empty();
            var encoded = value.get();
            local.put(key, encoded);
            return redis.opsForValue().set(redisKey(key), encoded, redisTtl)
                .onErrorResume(error -> {
                    logger.warn("L2 cache write failed namespace={} error_type={}",
                        namespace, error.getClass().getSimpleName());
                    return Mono.just(false);
                })
                .thenReturn(encoded);
        });
        return redis.opsForValue().get(redisKey(key))
            .doOnNext(value -> local.put(key, value))
            .onErrorResume(error -> {
                logger.warn("L2 cache read failed namespace={} error_type={}",
                    namespace, error.getClass().getSimpleName());
                return Mono.empty();
            })
            .switchIfEmpty(database)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    public Mono<Void> evict(String key) {
        local.invalidate(key);
        return redis.delete(redisKey(key))
            .onErrorResume(error -> {
                logger.warn("L2 cache eviction failed namespace={} error_type={}",
                    namespace, error.getClass().getSimpleName());
                return Mono.just(0L);
            })
            .then();
    }

    private String redisKey(String key) {
        return namespace + key;
    }
}
