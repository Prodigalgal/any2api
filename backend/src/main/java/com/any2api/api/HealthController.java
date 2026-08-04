package com.any2api.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class HealthController {
    private final JdbcClient jdbc;
    private final ReactiveStringRedisTemplate redis;
    private final ExecutorService databaseExecutor;

    public HealthController(
        JdbcClient jdbc,
        ReactiveStringRedisTemplate redis,
        ExecutorService databaseExecutor
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.databaseExecutor = databaseExecutor;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public Mono<ResponseEntity<Map<String, String>>> ready() {
        var postgres = Mono.fromCallable(() -> jdbc.sql("SELECT 1").query(Integer.class).single())
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
        var redisPing = redis.hasKey("any2api:readiness");
        return Mono.when(postgres, redisPing)
            .timeout(Duration.ofSeconds(3))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")))
            .onErrorReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN")));
    }
}
