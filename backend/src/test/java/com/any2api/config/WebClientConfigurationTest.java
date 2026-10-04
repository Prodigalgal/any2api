package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

class WebClientConfigurationTest {
    @Test
    void reusesActiveConnectionsButReplacesIdleConnectionsBeforeAutomationKeepAliveExpires()
        throws InterruptedException {
        var server = HttpServer.create().host("127.0.0.1").port(0)
            .idleTimeout(Duration.ofSeconds(10))
            .handle((request, response) -> {
                var connectionId = new String[1];
                request.withConnection(connection -> connectionId[0] = connection.channel().id().asLongText());
                return response.sendString(Mono.just(connectionId[0]));
            }).bindNow();
        ConnectionProvider provider;
        try (var context = new AnnotationConfigApplicationContext(WebClientConfiguration.class)) {
            provider = context.getBean(ConnectionProvider.class);
            var client = context.getBean(WebClient.Builder.class)
                .baseUrl("http://127.0.0.1:" + server.port()).build();
            var first = connectionId(client);
            // Body completion reaches the caller before the asynchronous pool release.
            Thread.sleep(Duration.ofMillis(200));
            assertThat(connectionId(client)).isEqualTo(first);

            Thread.sleep(Duration.ofSeconds(4));

            assertThat(connectionId(client)).isNotEqualTo(first);
        } finally {
            server.disposeNow();
        }
        assertThat(provider.isDisposed()).isTrue();
    }

    private String connectionId(WebClient client) {
        return client.get().uri("/").retrieve().bodyToMono(String.class)
            .block(Duration.ofSeconds(10));
    }
}
