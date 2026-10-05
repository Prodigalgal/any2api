package com.any2api.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.test.StepVerifier;

class AccountLeaseRedisClientIntegrationTest {
    private static final Duration TEST_COMMAND_TIMEOUT = Duration.ofMillis(500);

    @Test
    void bootKeepsOneDefaultFactoryAndTemplateAlongsideTheOwnedLeaseClient() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                DataRedisReactiveAutoConfiguration.class))
            .withBean(AccountLeaseRedisClient.class)
            .withBean(AccountLeaseService.class)
            .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1")
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(LettuceConnectionFactory.class)
                .hasSingleBean(ReactiveStringRedisTemplate.class)
                .hasSingleBean(AccountLeaseRedisClient.class)
                .hasSingleBean(AccountLeaseService.class));
    }

    @Test
    void timedOutCacheWriteBlocksTheOldConnectionWhileDedicatedLeaseStillCompletes() throws Exception {
        try (var server = new RedisWireServer(false)) {
            var primary = primary(server);
            var cache = new ReactiveStringRedisTemplate(primary);
            var leases = new AccountLeaseRedisClient(primary);
            var cacheWrite = cache.opsForValue().set("test:cache", "snapshot", Duration.ofSeconds(5))
                .timeout(Duration.ofMillis(50)).onErrorReturn(false);
            try {
                assertThat(cache.hasKey("readiness").block(Duration.ofSeconds(2))).isFalse();
                assertThat(leases.checkReadiness().block(Duration.ofSeconds(2))).isFalse();
                cacheWrite.subscribe();
                assertThat(server.cacheWriteReceived.await(2, TimeUnit.SECONDS)).isTrue();
                var script = RedisScript.of("return 42", Long.class);
                StepVerifier.create(cache.execute(script, List.of(), List.of()))
                    .expectError(QueryTimeoutException.class).verify(Duration.ofSeconds(3));
                StepVerifier.create(new AccountLeaseService(leases).acquire(
                        "mimo", UUID.randomUUID(), 1, Duration.ofMinutes(5)))
                    .assertNext(lease -> assertThat(lease.fencingToken()).isEqualTo(42))
                    .expectComplete().verify(Duration.ofSeconds(3));
                assertThat(server.failure.get()).isNull();
            } finally {
                server.releaseCommands.countDown();
                leases.destroy();
                primary.destroy();
            }
        }
    }

    @Test
    void dedicatedConnectionKeepsTheExistingTimeoutAndFailsClosed() throws Exception {
        try (var server = new RedisWireServer(true)) {
            var primary = primary(server);
            var leases = new AccountLeaseRedisClient(primary);
            try {
                leases.checkReadiness().block(Duration.ofSeconds(2));
                StepVerifier.create(new AccountLeaseService(leases).acquire(
                        "mimo", UUID.randomUUID(), 1, Duration.ofMinutes(5)))
                    .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(CoordinationUnavailableException.class)
                        .hasCauseInstanceOf(QueryTimeoutException.class))
                    .verify(Duration.ofSeconds(3));
            } finally {
                server.releaseCommands.countDown();
                leases.destroy();
                primary.destroy();
            }
        }
    }

    @Test
    void credentialsDatabaseAndLifecycleBelongToTheDedicatedClient() throws Exception {
        try (var server = new RedisWireServer(false)) {
            var primary = primary(server);
            var leases = new AccountLeaseRedisClient(primary);
            try {
                assertThat(leases.checkReadiness().block(Duration.ofSeconds(2))).isFalse();
                assertThat(server.authenticatedConnections).hasSize(1);
                assertThat(server.selectedDatabases).containsExactly("3");
                leases.destroy();
                assertThatThrownBy(() -> leases.checkReadiness().block(Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalStateException.class);
                var cache = new ReactiveStringRedisTemplate(primary);
                assertThat(cache.hasKey("readiness").block(Duration.ofSeconds(2))).isFalse();
                assertThat(server.authenticatedConnections).hasSize(2);
                assertThat(server.failure.get()).isNull();
            } finally {
                leases.destroy();
                primary.destroy();
            }
        }
    }

    private LettuceConnectionFactory primary(RedisWireServer server) {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", server.listener.getLocalPort());
        configuration.setUsername("fixture-user");
        configuration.setPassword("fixture-password");
        configuration.setDatabase(3);
        var client = LettuceClientConfiguration.builder()
            .commandTimeout(TEST_COMMAND_TIMEOUT)
            .shutdownTimeout(Duration.ZERO)
            .clientOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build())
            .build();
        var primary = new LettuceConnectionFactory(configuration, client);
        primary.afterPropertiesSet();
        primary.start();
        return primary;
    }

    private static final class RedisWireServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ConcurrentLinkedQueue<Socket> connections = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Socket> authenticatedConnections = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> selectedDatabases = new ConcurrentLinkedQueue<>();
        private final CountDownLatch cacheWriteReceived = new CountDownLatch(1);
        private final CountDownLatch releaseCommands = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final boolean blockLua;

        private RedisWireServer(boolean blockLua) throws IOException {
            this.blockLua = blockLua;
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        var connection = listener.accept();
                        connections.add(connection);
                        workers.submit(() -> serve(connection));
                    } catch (IOException error) {
                        if (!listener.isClosed()) failure.compareAndSet(null, error);
                    }
                }
            });
        }

        private void serve(Socket socket) {
            try (socket; var input = new BufferedInputStream(socket.getInputStream())) {
                while (!socket.isClosed()) {
                    var command = command(input);
                    String response = switch (command.getFirst()) {
                        case "AUTH" -> {
                            if (!command.subList(1, command.size()).equals(List.of("fixture-user", "fixture-password")))
                                throw new IOException("unexpected fixture authentication");
                            authenticatedConnections.add(socket);
                            yield "+OK\r\n";
                        }
                        case "SELECT" -> { selectedDatabases.add(command.get(1)); yield "+OK\r\n"; }
                        case "CLIENT" -> "+OK\r\n";
                        case "PING" -> "+PONG\r\n";
                        case "EXISTS" -> ":0\r\n";
                        case "SET" -> {
                            cacheWriteReceived.countDown();
                            releaseCommands.await();
                            yield "+OK\r\n";
                        }
                        case "EVALSHA", "EVAL" -> {
                            if (blockLua) releaseCommands.await();
                            yield ":42\r\n";
                        }
                        default -> throw new IOException("unexpected fixture command " + command.getFirst());
                    };
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
            } catch (EOFException ignored) {
                // The tested client closes its socket when its owner is destroyed.
            } catch (Exception error) {
                if (!listener.isClosed()) failure.compareAndSet(null, error);
            }
        }

        private static List<String> command(BufferedInputStream input) throws IOException {
            var header = line(input);
            if (!header.startsWith("*")) throw new IOException("expected RESP command");
            var arguments = new ArrayList<String>();
            for (int index = 0, count = Integer.parseInt(header.substring(1)); index < count; index++) {
                var length = Integer.parseInt(line(input).substring(1));
                if (length < 0 || length > 1024 * 1024) throw new IOException("unexpected fixture size");
                var value = input.readNBytes(length);
                if (value.length != length || !line(input).isEmpty()) throw new EOFException();
                arguments.add(new String(value, StandardCharsets.UTF_8));
            }
            return arguments;
        }

        private static String line(BufferedInputStream input) throws IOException {
            var bytes = new ByteArrayOutputStream();
            for (int next; (next = input.read()) != -1;) {
                if (next == '\r') {
                    if (input.read() != '\n') throw new IOException("expected RESP newline");
                    return bytes.toString(StandardCharsets.UTF_8);
                }
                bytes.write(next);
            }
            throw new EOFException();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            releaseCommands.countDown();
            for (var connection : connections) connection.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
