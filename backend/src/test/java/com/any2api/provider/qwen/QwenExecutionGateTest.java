package com.any2api.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class QwenExecutionGateTest {

    @Test
    void serializesWholeProviderWorkflows() throws Exception {
        var gate = new QwenExecutionGate();
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = Sinks.<Void>empty();
        var first = gate.mono(() -> {
            firstEntered.countDown();
            return releaseFirst.asMono().thenReturn("first");
        }).toFuture();

        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
        var second = gate.mono(() -> {
            secondEntered.countDown();
            return Mono.just("second");
        }).toFuture();

        assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
        releaseFirst.tryEmitEmpty();
        assertThat(first.get()).isEqualTo("first");
        assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get()).isEqualTo("second");
    }
}
