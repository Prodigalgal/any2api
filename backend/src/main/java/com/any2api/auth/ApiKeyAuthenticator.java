package com.any2api.auth;

import com.any2api.cache.LayeredJsonCache;
import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApiKeyAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);
    private static final Duration USAGE_WRITE_INTERVAL = Duration.ofMinutes(1);
    private final ApiKeyRepository keys;
    private final ApiKeyGrantStore grantStore;
    private final ObjectMapper mapper;
    private final LayeredJsonCache grants;
    private final ConcurrentHashMap<UUID, Instant> usageWrites = new ConcurrentHashMap<>();

    public ApiKeyAuthenticator(
        ApiKeyRepository keys,
        ApiKeyGrantStore grantStore,
        ReactiveStringRedisTemplate redis,
        ObjectMapper mapper,
        Any2ApiProperties properties
    ) {
        this.keys = keys;
        this.grantStore = grantStore;
        this.mapper = mapper;
        var cache = properties.getCache().getApiKey();
        this.grants = new LayeredJsonCache(
            redis, "any2api:cache:api-key:v2", cache.getLocalTtl(),
            cache.getRedisTtl(), cache.getMaximumEntries());
    }

    public Mono<Optional<ApiKeyGrant>> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.length() < 24 || presentedKey.length() > 256) {
            return Mono.just(Optional.empty());
        }
        var hash = hash(presentedKey);
        return grants.get(hash, () -> Mono.fromCallable(() -> load(hash))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(result -> decodeOrReload(hash, result))
            .flatMap(result -> {
                if (result.isPresent() && result.get().expired(Instant.now())) {
                    return grants.evict(hash).thenReturn(Optional.empty());
                }
                if (result.isEmpty()) return Mono.just(result);
                return Mono.fromCallable(() -> {
                    touch(result.get());
                    return result;
                }).subscribeOn(Schedulers.boundedElastic());
            });
    }

    Mono<Void> invalidate(String keyHash) {
        return grants.evict(keyHash);
    }

    static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private Optional<String> load(String hash) {
        return keys.findByKeyHashAndEnabledTrue(hash)
            .map(grantStore::read)
            .map(mapper::writeValueAsString);
    }

    private Mono<Optional<ApiKeyGrant>> decodeOrReload(
        String hash,
        Optional<String> encoded
    ) {
        try {
            return Mono.just(encoded.map(value -> mapper.readValue(value, ApiKeyGrant.class)));
        } catch (RuntimeException invalidSnapshot) {
            log.warn("API key cache snapshot was invalid and will be rebuilt");
            return grants.evict(hash)
                .then(Mono.fromCallable(() -> load(hash)
                    .map(value -> mapper.readValue(value, ApiKeyGrant.class)))
                    .subscribeOn(Schedulers.boundedElastic()));
        }
    }

    private void touch(ApiKeyGrant grant) {
        if (grant.keyId() == null) return;
        var now = Instant.now();
        usageWrites.compute(grant.keyId(), (id, previous) -> {
            if (previous == null || previous.plus(USAGE_WRITE_INTERVAL).isBefore(now)) {
                keys.markUsed(id, now);
                return now;
            }
            return previous;
        });
    }
}
