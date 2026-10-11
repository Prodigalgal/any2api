package com.any2api.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.config.Any2ApiProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ProviderEgressGateTest {

    private Any2ApiProperties properties;
    private ProviderEgressGate gate;

    @BeforeEach
    void setUp() {
        properties = new Any2ApiProperties();
        properties.getEgressGate().setEnabled(true);
        properties.getEgressGate().setDefaultNodeConcurrency(1);
        properties.getEgressGate().setDefaultProviderConcurrency(2);
        properties.getEgressGate().setDefaultStagger(Duration.ofMillis(50));
        gate = new ProviderEgressGate(properties);
    }

    @Test
    void resolvesEgressNodeKeyCorrectly() {
        assertThat(gate.resolveEgressNodeKey(null, "")).isEqualTo("direct");
        assertThat(gate.resolveEgressNodeKey(Map.of(), "")).isEqualTo("direct");

        var proxyWithNodes = Map.<String, Object>of("nodes", List.of("http://192.168.1.10:8080", "http://192.168.1.20:8080"));
        assertThat(gate.resolveEgressNodeKey(proxyWithNodes, ""))
            .isEqualTo("192.168.1.10:8080");

        var proxyWithSub = Map.<String, Object>of("subscription_url", "https://subscribe.example.com/sub?token=123");
        assertThat(gate.resolveEgressNodeKey(proxyWithSub, ""))
            .isEqualTo("subscribe.example.com");
    }

    @Test
    void limitsNodeConcurrencyToOne() throws Exception {
        var proxy = Map.<String, Object>of("nodes", List.of("http://1.2.3.4:8080"));
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = Sinks.<Void>empty();

        var first = gate.wrapMono("qwen", proxy, "acc-1", () -> {
            firstEntered.countDown();
            return releaseFirst.asMono().thenReturn("first-done");
        }).toFuture();

        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

        var second = gate.wrapMono("qwen", proxy, "acc-1", () -> {
            secondEntered.countDown();
            return Mono.just("second-done");
        }).toFuture();

        // Second should be blocked while first is running
        assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();

        // Release first
        releaseFirst.tryEmitEmpty();
        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first-done");

        // Second should now enter and complete
        assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("second-done");
    }

    @Test
    void allowsDifferentNodesToRunConcurrentlyUpToProviderLimit() throws Exception {
        var node1 = Map.<String, Object>of("nodes", List.of("http://1.1.1.1:8080"));
        var node2 = Map.<String, Object>of("nodes", List.of("http://2.2.2.2:8080"));

        var enteredLatch = new CountDownLatch(2);
        var releaseSink = Sinks.<Void>empty();

        var fut1 = gate.wrapMono("qwen", node1, "", () -> {
            enteredLatch.countDown();
            return releaseSink.asMono().thenReturn("node1-done");
        }).toFuture();

        var fut2 = gate.wrapMono("qwen", node2, "", () -> {
            enteredLatch.countDown();
            return releaseSink.asMono().thenReturn("node2-done");
        }).toFuture();

        // Both nodes can enter simultaneously because providerConcurrency=2 and they are different nodes
        assertThat(enteredLatch.await(2, TimeUnit.SECONDS)).isTrue();

        releaseSink.tryEmitEmpty();
        assertThat(fut1.get(2, TimeUnit.SECONDS)).isEqualTo("node1-done");
        assertThat(fut2.get(2, TimeUnit.SECONDS)).isEqualTo("node2-done");
    }

    @Test
    void triggersCooldownOn429() {
        var proxy = Map.<String, Object>of("nodes", List.of("http://bad-node:8080"));
        gate.recordRiskCooldown("bad-node:8080:qwen", "test_429");

        assertThatThrownBy(() -> gate.wrapMono("qwen", proxy, "", () -> Mono.just("ok")).block())
            .hasMessageContaining("egress node is in risk cooldown");
    }
}
