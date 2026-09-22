package com.any2api.cache;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.UsageSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Provides layered (Caffeine + Redis) exact response caching for non-streaming
 * inference requests to protect reverse-engineered accounts from repeated bursts.
 */
@Component
public class PromptExactCacheManager {

    private static final Logger log = LoggerFactory.getLogger(PromptExactCacheManager.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final com.github.benmanes.caffeine.cache.Cache<String, String> localCache;
    private final LayeredJsonCache layeredCache;
    private final ObjectMapper mapper;

    public PromptExactCacheManager(
        org.springframework.beans.factory.ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
        ObjectMapper mapper
    ) {
        this.mapper = mapper;
        this.localCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(DEFAULT_TTL)
            .build();
        var redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        this.layeredCache = redis == null ? null : new LayeredJsonCache(
            redis,
            "any2api:cache:prompt",
            DEFAULT_TTL,
            DEFAULT_TTL,
            10_000
        );
    }

    public Mono<List<CanonicalEvent>> get(CanonicalRequest request) {
        if (!isEligibleForCache(request)) {
            return Mono.empty();
        }
        var key = cacheKey(request);
        var readMono = layeredCache != null
            ? layeredCache.get(key, () -> Mono.just(Optional.empty()))
            : Mono.just(Optional.ofNullable(localCache.getIfPresent(key)));
        return readMono
            .flatMap(optional -> {
                if (optional.isEmpty()) {
                    return Mono.empty();
                }
                try {
                    List<StoredEvent> stored = mapper.readValue(
                        optional.get(), new TypeReference<List<StoredEvent>>() {});
                    var reconstructed = new ArrayList<CanonicalEvent>();
                    for (var item : stored) {
                        if ("content".equals(item.type())) {
                            reconstructed.add(new CanonicalEvent.OutputTextDelta(
                                item.schemaVersion(), request.requestId(), item.sequenceNumber(), item.delta()));
                        } else if ("usage".equals(item.type())) {
                            reconstructed.add(new CanonicalEvent.Usage(
                                item.schemaVersion(), request.requestId(), item.sequenceNumber(),
                                item.inputTokens(), item.outputTokens(), item.inputTokens(),
                                UsageSource.UPSTREAM));
                        } else if ("completed".equals(item.type())) {
                            reconstructed.add(new CanonicalEvent.Completed(
                                item.schemaVersion(), request.requestId(), item.sequenceNumber(), item.finishReason()));
                        }
                    }
                    log.info("prompt_cache_hit request_id={} provider={} model={}",
                        request.requestId(), request.providerId(), request.model());
                    return Mono.just(List.copyOf(reconstructed));
                } catch (Exception parseError) {
                    log.warn("prompt_cache_parse_failed key={}", key, parseError);
                    return Mono.empty();
                }
            });
    }

    public Mono<Void> put(CanonicalRequest request, List<CanonicalEvent> events) {
        if (!isEligibleForCache(request) || events == null || events.isEmpty()) {
            return Mono.empty();
        }
        boolean hasFailed = events.stream().anyMatch(CanonicalEvent.Failed.class::isInstance);
        boolean hasCompleted = events.stream().anyMatch(CanonicalEvent.Completed.class::isInstance);
        if (hasFailed || !hasCompleted) {
            return Mono.empty();
        }

        var key = cacheKey(request);
        var stored = new ArrayList<StoredEvent>();
        for (var event : events) {
            if (event instanceof CanonicalEvent.OutputTextDelta delta) {
                stored.add(new StoredEvent("content", delta.schemaVersion(), delta.sequenceNumber(),
                    delta.delta(), null, 0, 0));
            } else if (event instanceof CanonicalEvent.Usage usage) {
                stored.add(new StoredEvent("usage", usage.schemaVersion(), usage.sequenceNumber(),
                    null, null, usage.inputTokens(), usage.outputTokens()));
            } else if (event instanceof CanonicalEvent.Completed completed) {
                stored.add(new StoredEvent("completed", completed.schemaVersion(), completed.sequenceNumber(),
                    null, completed.finishReason(), 0, 0));
            }
        }

        return Mono.fromCallable(() -> mapper.writeValueAsString(stored))
            .flatMap(payload -> {
                localCache.put(key, payload);
                if (layeredCache != null) {
                    return layeredCache.get(key, () -> Mono.just(Optional.of(payload))).then();
                }
                return Mono.empty();
            })
            .doOnSuccess(ignored -> log.debug("prompt_cache_stored key={}", key))
            .then();
    }

    public boolean isEligibleForCache(CanonicalRequest request) {
        if (request == null || request.stream()) {
            return false;
        }
        // If high temperature is explicitly requested (e.g. creative/random generation > 1.2), skip cache
        var temp = request.generation().get("temperature");
        if (temp instanceof Number num && num.doubleValue() > 1.2) {
            return false;
        }
        return true;
    }

    private String cacheKey(CanonicalRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var sb = new StringBuilder();
            sb.append(request.protocol()).append("|")
              .append(request.providerId()).append("|")
              .append(request.model()).append("|");
            for (var msg : request.messages()) {
                sb.append(msg.toString()).append(";");
            }
            sb.append("|").append(request.generation().toString());
            var hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record StoredEvent(
        String type,
        int schemaVersion,
        long sequenceNumber,
        String delta,
        String finishReason,
        long inputTokens,
        long outputTokens
    ) {}
}
