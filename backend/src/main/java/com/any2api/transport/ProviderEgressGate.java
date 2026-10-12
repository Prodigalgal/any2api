package com.any2api.transport;

import com.any2api.config.Any2ApiProperties;
import java.net.URI;
import java.time.Duration;
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
    private final AtomicLong roundRobinCounter = new AtomicLong(0);

    public ProviderEgressGate(Any2ApiProperties properties) {
        this.properties = properties;
    }

    public <T> Mono<T> wrapMono(
        String providerId,
        Map<String, Object> proxyPool,
        String affinityKey,
        Supplier<Mono<T>> work
    ) {
        if (!properties.getEgressGate().isEnabled()) {
            return Mono.defer(work);
        }

        var nodeKey = resolveEgressNodeKey(proxyPool, affinityKey);
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
        );
    }

    public <T> Flux<T> wrapFlux(
        String providerId,
        Map<String, Object> proxyPool,
        String affinityKey,
        Supplier<Flux<T>> work
    ) {
        if (!properties.getEgressGate().isEnabled()) {
            return Flux.defer(work);
        }

        var nodeKey = resolveEgressNodeKey(proxyPool, affinityKey);
        var compositeKey = nodeKey + ":" + providerId;
        var globalKey = "global:" + providerId;

        return Flux.usingWhen(
            acquirePermit(providerId, compositeKey, globalKey),
            permit -> applyStagger(compositeKey)
                .thenMany(Flux.defer(work)),
            Permit::release,
            (permit, err) -> {
                recordFailure(compositeKey, err);
                return permit.release();
            },
            Permit::release
        );
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

            var timeoutMs = properties.getEgressGate().getAcquireTimeout().toMillis();
            if (!providerSem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("provider global concurrency limit exceeded for " + providerId);
            }

            try {
                if (!nodeSem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("egress node concurrency limit exceeded for " + compositeKey);
                }
            } catch (Exception ex) {
                providerSem.release();
                throw ex;
            }

            return new Permit(nodeSem, providerSem);
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
            var last = lastTimeHolder.get();
            var requiredWait = (last + stagger.toMillis()) - now;

            lastTimeHolder.set(Math.max(now, last + stagger.toMillis()));

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
        }
    }

    private void recordFailure(String compositeKey, Throwable err) {
        var msg = err != null ? err.getMessage() : "";
        if (msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit")
            || msg.toLowerCase().contains("risk"))) {
            recordRiskCooldown(compositeKey, msg);
        }
    }

    public void recordRiskCooldown(String compositeKey, String reason) {
        LOGGER.warn("egress_node_cooldown_triggered key={} reason={}", compositeKey, reason);
        nodeCooldowns.put(compositeKey, System.currentTimeMillis() + COOLDOWN_DURATION.toMillis());
    }

    public String resolveEgressNodeKey(Map<String, Object> proxyPool, String affinityKey) {
        if (proxyPool == null || proxyPool.isEmpty()) {
            return "direct";
        }

        if (proxyPool.get("nodes") instanceof List<?> rawList && !rawList.isEmpty()) {
            var nodes = rawList.stream().map(String::valueOf).toList();
            if (affinityKey != null && !affinityKey.isBlank()) {
                // Consistent hash affinity
                var idx = Math.abs(affinityKey.hashCode()) % nodes.size();
                return sanitizeNodeAddress(nodes.get(idx));
            }
            var idx = (int) (Math.abs(roundRobinCounter.getAndIncrement()) % nodes.size());
            return sanitizeNodeAddress(nodes.get(idx));
        }

        if (proxyPool.get("subscription_url") instanceof String sub && !sub.isBlank()) {
            try {
                var host = URI.create(sub).getHost();
                return host != null ? host : "sub";
            } catch (Exception ignored) {
                return "sub";
            }
        }

        return "pool:default";
    }

    private static String sanitizeNodeAddress(String node) {
        try {
            var uri = URI.create(node);
            if (uri.getHost() != null) {
                return uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 443);
            }
        } catch (Exception ignored) {}
        return "node:" + Math.abs(node.hashCode() % 1000);
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
            return Mono.fromRunnable(() -> {
                if (released.compareAndSet(false, true)) {
                    nodeSemaphore.release();
                    providerSemaphore.release();
                }
            });
        }
    }
}
