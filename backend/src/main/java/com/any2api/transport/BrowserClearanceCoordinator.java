package com.any2api.transport;

import com.any2api.credential.SecretCipher;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class BrowserClearanceCoordinator {
    private static final Pattern BINDING_ID = Pattern.compile("^[a-f0-9]{64}$");
    private static final Duration LOCK_TTL = Duration.ofMinutes(3);
    private static final Duration WAIT_LIMIT = Duration.ofMinutes(3);
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_CACHE_TTL = Duration.ofMinutes(30);
    private static final Set<String> CONTEXT_FIELDS = Set.of(
        "cloudflare_cookies", "user_agent", "browser_profile",
        "clearance_refreshed_at", "clearance_expires_at");
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
        if redis.call('get', KEYS[1]) == ARGV[1] then
          return redis.call('del', KEYS[1])
        end
        return 0
        """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final BrowserTransportClient transport;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;

    public BrowserClearanceCoordinator(
        ReactiveStringRedisTemplate redis,
        BrowserTransportClient transport,
        SecretCipher cipher,
        ObjectMapper mapper
    ) {
        this.redis = redis;
        this.transport = transport;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    public Mono<JsonNode> recover(BrowserTransportClient.Session session) {
        var bindingId = requireBindingId(session.bindingId());
        var cacheKey = cacheKey(bindingId);
        return redis.opsForValue().get(cacheKey).defaultIfEmpty("")
            .flatMap(baseline -> acquireOrWait(
                session, bindingId, baseline, Instant.now().plus(WAIT_LIMIT)));
    }

    private Mono<JsonNode> acquireOrWait(
        BrowserTransportClient.Session session,
        String bindingId,
        String baseline,
        Instant deadline
    ) {
        var owner = UUID.randomUUID().toString();
        return redis.opsForValue().setIfAbsent(lockKey(bindingId), owner, LOCK_TTL)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "browser clearance coordination unavailable")))
            .flatMap(acquired -> acquired
                ? refreshAndCache(session, bindingId)
                    .flatMap(context -> release(bindingId, owner).thenReturn(context))
                    .onErrorResume(error -> release(bindingId, owner)
                        .then(Mono.error(error)))
                : awaitResult(session, bindingId, baseline, deadline));
    }

    private Mono<JsonNode> refreshAndCache(
        BrowserTransportClient.Session session,
        String bindingId
    ) {
        return transport.refreshClearance(session.id(), "/index")
            .map(this::sanitize)
            .flatMap(context -> {
                var encoded = encrypt(bindingId, context);
                return redis.opsForValue().set(
                        cacheKey(bindingId), encoded, cacheTtl(context))
                    .flatMap(stored -> stored
                        ? Mono.just(context)
                        : Mono.error(new IllegalStateException(
                            "browser clearance cache is unavailable")));
            });
    }

    private Mono<JsonNode> awaitResult(
        BrowserTransportClient.Session session,
        String bindingId,
        String baseline,
        Instant deadline
    ) {
        if (Instant.now().isAfter(deadline)) {
            return Mono.error(new IllegalStateException(
                "browser clearance refresh timed out"));
        }
        return Mono.delay(Duration.ofMillis(250 + java.util.concurrent.ThreadLocalRandom
                .current().nextLong(251)))
            .then(redis.opsForValue().get(cacheKey(bindingId)).defaultIfEmpty(""))
            .flatMap(encoded -> {
                if (!encoded.isBlank() && !encoded.equals(baseline)) {
                    var context = decrypt(bindingId, encoded);
                    return transport.applyClearance(session.id(), context)
                        .thenReturn(context);
                }
                return redis.hasKey(lockKey(bindingId)).flatMap(locked -> locked
                    ? awaitResult(session, bindingId, baseline, deadline)
                    : acquireOrWait(session, bindingId, baseline, deadline));
            });
    }

    private Mono<Long> release(String bindingId, String owner) {
        return redis.execute(RELEASE, List.of(lockKey(bindingId)), List.of(owner))
            .singleOrEmpty().defaultIfEmpty(0L);
    }

    private ObjectNode sanitize(JsonNode source) {
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("browser clearance context must be an object");
        }
        var result = mapper.createObjectNode();
        source.properties().forEach(entry -> {
            if (CONTEXT_FIELDS.contains(entry.getKey()) && entry.getValue().isTextual()) {
                var value = entry.getValue().asText("").trim();
                if (!value.isBlank()) result.put(entry.getKey(), value);
            }
        });
        if (result.path("cloudflare_cookies").asText("").isBlank()
            || result.path("user_agent").asText("").isBlank()
            || result.path("browser_profile").asText("").isBlank()) {
            throw new IllegalArgumentException(
                "browser clearance context is incomplete");
        }
        return result;
    }

    private String encrypt(String bindingId, JsonNode context) {
        var sealed = cipher.seal(
            mapper.writeValueAsBytes(context), aad(bindingId));
        return mapper.writeValueAsString(mapper.createObjectNode()
            .put("payload", Base64.getEncoder().encodeToString(sealed.encrypted()))
            .put("nonce", Base64.getEncoder().encodeToString(sealed.nonce()))
            .put("key_version", sealed.keyVersion()));
    }

    private JsonNode decrypt(String bindingId, String encoded) {
        try {
            var envelope = mapper.readTree(encoded);
            var encrypted = Base64.getDecoder().decode(
                envelope.path("payload").asText(""));
            var nonce = Base64.getDecoder().decode(
                envelope.path("nonce").asText(""));
            return sanitize(mapper.readTree(cipher.open(
                encrypted, nonce, aad(bindingId))));
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "cached browser clearance context is invalid", error);
        }
    }

    private Duration cacheTtl(JsonNode context) {
        var raw = context.path("clearance_expires_at").asText("").trim();
        if (raw.isBlank()) return DEFAULT_CACHE_TTL;
        try {
            var remaining = Duration.between(Instant.now(), Instant.parse(raw));
            if (remaining.compareTo(Duration.ofSeconds(30)) < 0) {
                return Duration.ofSeconds(30);
            }
            return remaining.compareTo(MAX_CACHE_TTL) > 0 ? MAX_CACHE_TTL : remaining;
        } catch (DateTimeParseException ignored) {
            return DEFAULT_CACHE_TTL;
        }
    }

    private String requireBindingId(String value) {
        var normalized = value == null ? "" : value.trim();
        if (!BINDING_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("browser session binding id is invalid");
        }
        return normalized;
    }

    private String cacheKey(String bindingId) {
        return "any2api:browser-clearance:{" + bindingId + "}:context";
    }

    private String lockKey(String bindingId) {
        return "any2api:browser-clearance:{" + bindingId + "}:lock";
    }

    private String aad(String bindingId) {
        return "browser-clearance:" + bindingId;
    }
}
