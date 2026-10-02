package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

class WebFluxBlockingExecutionTest {
    @Test
    void blockingControllerDoesNotHoldTheSingleHttpEventLoop() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            var controller = context.getBean(BlockingController.class);
            var loops = LoopResources.create("read-isolation-test", 1, true);
            var handler = new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build());
            var server = HttpServer.create().host("127.0.0.1").port(0).runOn(loops)
                .handle(handler).bindNow();
            try {
                var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + server.port())
                    .responseTimeout(Duration.ofSeconds(2)).build();
                var blocking = CompletableFuture.runAsync(() -> client.get().uri("/blocking").exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.virtualThread").isEqualTo(true));
                assertThat(controller.started.await(2, TimeUnit.SECONDS)).isTrue();
                try {
                    client.get().uri("/non-blocking").exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.status").isEqualTo("UP");
                } finally {
                    controller.release.countDown();
                }
                blocking.get(3, TimeUnit.SECONDS);
            } finally {
                controller.release.countDown();
                server.disposeNow();
                loops.disposeLater().block(Duration.ofSeconds(3));
            }
        }
    }

    @Configuration
    @EnableWebFlux
    @Import({ExecutorConfiguration.class, WebFluxConfiguration.class})
    static class TestConfiguration {
        @Bean BlockingController blockingController() { return new BlockingController(); }
    }

    @RestController
    static class BlockingController {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @GetMapping("/blocking")
        public Map<String, Boolean> blocking() throws InterruptedException {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test request was not released");
            return Map.of("virtualThread", Thread.currentThread().isVirtual());
        }

        @GetMapping("/non-blocking")
        public Mono<Map<String, String>> nonBlocking() { return Mono.just(Map.of("status", "UP")); }
    }
}
