package com.any2api.transport;

import com.any2api.config.Any2ApiProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Hierarchical Egress Rate-Limiter and Concurrency Gate.
 * Enforces:
 * 1. Per-Egress-Node (IP) & Provider concurrency limit to prevent triggering upstream WAF / anti-bot.
 * 2. Per-Provider global concurrency ceiling.
 * 3. Inter-request staggering (jitter / spacing) on the same IP:Provider to eliminate machine-burst fingerprints.
 * 4. Adaptive cooldown when upstream returns 429 or anti-bot blocks.
 */
@Component
public class ProviderEgressGate {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderEgressGate.class);
    private static final Duration COOLDOWN_DURATION = Duration.ofSeconds(30);

    private final Any2ApiProperties properties;
    private final ConcurrentMap<String, Semaphore> nodeSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> providerSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> lastRequestTimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> nodeCooldowns = new ConcurrentHashMap<>();

    public ProviderEgressGate(Any2ApiProperties properties) {
        this.properties = properties;
    }

    public <T> Mono<T> wrapMono(
        String providerId,
        Map<String, Object> proxyPool,
        String affinityKey,
        Supplier<Mono<T>> work
    ) {
        return wrapMono(providerId, proxyPool, affinityKey, 0, work);
    }

    public <T> Mono<T> wrapMono(
        String providerId, Map<String, Object> proxyPool, String affinityKey,
        int nodeOffset, Supplier<Mono<T>> work
    ) {
        if (!properties.getEgressGate().isEnabled()) {
            return Mono.defer(work);
        }

        var nodeKey = resolveEgressNodeKey(proxyPool, affinityKey, nodeOffset);
        var compositeKey = nodeKey + ":" + providerId;
        var globalKey = "global:" + providerId;

        return Mono.usingWhen(
            acquirePermit(providerId, compositeKey, globalKey),
            permit -> applyStagger(compositeKey)
                .then(Mono.defer(work))
                .doOnSuccess(res -> checkAndRecordResult(compositeKey, res)),
            Permit::release,
            (permit, err) -> {
                recordFailure(compositeKey, err);
                return permit.release();
            },
            Permit::release
        ).doOnDiscard(Permit.class, Permit::releaseNow);
    }

    public <T> Flux<T> wrapFlux(
        String providerId,
        Map<String, Object> proxyPool,
        String affinityKey,
        Supplier<Flux<T>> work
    ) {
        return wrapFlux(providerId, proxyPool, affinityKey, 0, work);
    }

    public <T> Flux<T> wrapFlux(
        String providerId, Map<String, Object> proxyPool, String affinityKey,
        int nodeOffset, Supplier<Flux<T>> work
    ) {
        if (!properties.getEgressGate().isEnabled()) {
            return Flux.defer(work);
        }

        var nodeKey = resolveEgressNodeKey(proxyPool, affinityKey, nodeOffset);
        var compositeKey = nodeKey + ":" + providerId;
        var globalKey = "global:" + providerId;

        return Flux.usingWhen(
            acquirePermit(providerId, compositeKey, globalKey),
            permit -> applyStagger(compositeKey)
                .thenMany(Flux.defer(work))
                .doOnNext(result -> checkAndRecordResult(compositeKey, result)),
            Permit::release,
            (permit, err) -> {
                recordFailure(compositeKey, err);
                return permit.release();
            },
            Permit::release
        ).doOnDiscard(Permit.class, Permit::releaseNow);
    }

    private Mono<Permit> acquirePermit(String providerId, String compositeKey, String globalKey) {
        return Mono.fromCallable(() -> {
            checkCooldown(compositeKey);

            var providerSem = providerSemaphores.computeIfAbsent(globalKey, k -> {
                var max = properties.getEgressGate().getProviderConcurrency()
                    .getOrDefault(providerId, properties.getEgressGate().getDefaultProviderConcurrency());
                return new Semaphore(Math.max(1, max), true);
            });

            var nodeSem = nodeSemaphores.computeIfAbsent(compositeKey, k ->
                new Semaphore(Math.max(1, properties.getEgressGate().getDefaultNodeConcurrency()), true)
            );

            var timeoutNanos = properties.getEgressGate().getAcquireTimeout().toNanos();
            var startedAt = System.nanoTime();
            if (!providerSem.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("provider global concurrency limit exceeded for " + providerId);
            }

            boolean nodeAcquired = false;
            boolean granted = false;
            try {
                var remaining = Math.max(0, timeoutNanos - (System.nanoTime() - startedAt));
                nodeAcquired = nodeSem.tryAcquire(remaining, TimeUnit.NANOSECONDS);
                if (!nodeAcquired) {
                    throw new IllegalStateException("egress node concurrency limit exceeded for " + compositeKey);
                }
                checkCooldown(compositeKey);
                var permit = new Permit(nodeSem, providerSem);
                granted = true;
                return permit;
            } finally {
                if (!granted) {
                    if (nodeAcquired) nodeSem.release();
                    providerSem.release();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> applyStagger(String compositeKey) {
        var stagger = properties.getEgressGate().getDefaultStagger();
        if (stagger.isZero() || stagger.isNegative()) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            var lastTimeHolder = lastRequestTimes.computeIfAbsent(compositeKey, k -> new AtomicLong(0));
            var now = System.currentTimeMillis();
            var reservedAt = lastTimeHolder.updateAndGet(last -> Math.max(now, last + stagger.toMillis()));
            var requiredWait = reservedAt - now;

            if (requiredWait > 0) {
                return Mono.delay(Duration.ofMillis(requiredWait)).then();
            }
            return Mono.empty();
        });
    }

    private void checkCooldown(String compositeKey) {
        var until = nodeCooldowns.get(compositeKey);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                LOGGER.warn("egress_key_in_cooldown key={}", compositeKey);
                throw new IllegalStateException("egress node is in risk cooldown: " + compositeKey);
            } else {
                nodeCooldowns.remove(compositeKey);
            }
        }
    }

    private void checkAndRecordResult(String compositeKey, Object res) {
        if (res instanceof OfficialBrowserTransportClient.TransportResponse tr) {
            if (tr.status() == 429) {
                recordRiskCooldown(compositeKey, "upstream_429");
            }
        } else if (res instanceof tools.jackson.databind.JsonNode frame
            && "status".equals(frame.path("type").asText()) && frame.path("status").asInt() == 429) {
            recordRiskCooldown(compositeKey, "upstream_stream_429");
        }
    }

    private void recordFailure(String compositeKey, Throwable err) {
        var msg = err != null ? err.getMessage() : "";
        if (msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit")
            || msg.toLowerCase().contains("risk"))) {
            recordRiskCooldown(compositeKey, "upstream_error_signal");
        }
    }

    public void recordRiskCooldown(String compositeKey, String reason) {
        LOGGER.warn("egress_node_cooldown_triggered key={} reason={}", compositeKey, reason);
        nodeCooldowns.put(compositeKey, System.currentTimeMillis() + COOLDOWN_DURATION.toMillis());
    }

    public String resolveEgressNodeKey(Map<String, Object> proxyPool, String affinityKey) {
        return resolveEgressNodeKey(proxyPool, affinityKey, 0);
    }

    public String resolveEgressNodeKey(Map<String, Object> proxyPool, String affinityKey, int nodeOffset) {
        if (nodeOffset < 0 || nodeOffset > 100_000) {
            throw new IllegalArgumentException("proxy node offset is outside the allowed range");
        }
        if (proxyPool == null || proxyPool.isEmpty()) {
            return "direct";
        }

        if (proxyPool.get("nodes") instanceof List<?> rawList && !rawList.isEmpty()) {
            var nodes = rawList.stream().map(String::valueOf).toList();
            if (affinityKey != null && !affinityKey.isBlank()) {
                if (!affinityKey.matches("[A-Za-z0-9._:-]{1,256}")) {
                    throw new IllegalArgumentException("proxy affinity key is invalid");
                }
                var ordered = nodes.stream().sorted(Comparator.comparing(
                    (String node) -> sha256(affinityKey + "\0" + nodeIdentity(node))).reversed()).toList();
                return sanitizeNodeAddress(ordered.get(nodeOffset % ordered.size()));
            }
            if (nodes.size() == 1) return sanitizeNodeAddress(nodes.getFirst());
            // Python randomizes an unpinned pool. Use one conservative pool gate
            // instead of applying an unrelated round-robin node prediction.
            return "pool:" + sha256(String.join("\0", nodes.stream().sorted().toList())).substring(0, 24);
        }

        if (proxyPool.get("subscription_url") instanceof String sub && !sub.isBlank()) {
            try {
                var host = URI.create(sub).getHost();
                return host != null ? host : "sub";
            } catch (IllegalArgumentException ignored) {
                return "sub";
            }
        }

        return "pool:default";
    }

    private static String nodeIdentity(String node) {
        var uri = URI.create(node);
        if (!"vless".equals(uri.getScheme())) return node;
        var host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("proxy node host is missing");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        host = host.toLowerCase(java.util.Locale.ROOT);
        var user = uri.getRawUserInfo();
        user = user == null ? "" : user.split(":", 2)[0];
        var path = "/";
        for (var part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
            var pair = part.split("=", 2);
            if (pair.length == 2 && "path".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))
                && !pair[1].isEmpty()) {
                path = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                // Python parse_qs decodes '+' once; the subsequent unquote preserves it.
                path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
                break;
            }
        }
        return user + "@" + host + ":" + (uri.getPort() >= 0 ? uri.getPort() : 443)
            + (path.startsWith("/") ? path : "/" + path);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String sanitizeNodeAddress(String node) {
        try {
            var uri = URI.create(node);
            if (uri.getHost() != null) {
                return uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 443);
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed legacy nodes retain an opaque key without exposing credentials.
        }
        return "node:" + sha256(node).substring(0, 24);
    }

    private static final class Permit {
        private final Semaphore nodeSemaphore;
        private final Semaphore providerSemaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore nodeSemaphore, Semaphore providerSemaphore) {
            this.nodeSemaphore = nodeSemaphore;
            this.providerSemaphore = providerSemaphore;
        }

        public Mono<Void> release() {
            return Mono.fromRunnable(this::releaseNow);
        }

        private void releaseNow() {
            if (released.compareAndSet(false, true)) {
                nodeSemaphore.release();
                providerSemaphore.release();
            }
        }
    }
}
