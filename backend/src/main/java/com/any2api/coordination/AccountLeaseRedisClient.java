package com.any2api.coordination;

import java.util.List;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public final class AccountLeaseRedisClient implements DisposableBean {
    private final LettuceConnectionFactory connectionFactory;
    private final ReactiveStringRedisTemplate redis;

    public AccountLeaseRedisClient(LettuceConnectionFactory primaryConnectionFactory) {
        // Cache cancellation cannot remove bytes already queued on a shared TCP connection.
        // Keep fencing commands on their own connection with the same credentials and budgets.
        this.connectionFactory = new LettuceConnectionFactory(
            configuration(primaryConnectionFactory), primaryConnectionFactory.getClientConfiguration());
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();
        this.redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    public Flux<Long> execute(RedisScript<Long> script, List<String> keys, List<String> arguments) {
        return redis.execute(script, keys, arguments);
    }

    public Mono<Boolean> checkReadiness() {
        return redis.hasKey("any2api:readiness");
    }

    @Override
    public void destroy() {
        connectionFactory.destroy();
    }

    private static RedisConfiguration configuration(LettuceConnectionFactory primary) {
        if (primary.getSentinelConfiguration() != null) return primary.getSentinelConfiguration();
        if (primary.getClusterConfiguration() != null) return primary.getClusterConfiguration();
        return primary.getStandaloneConfiguration();
    }
}
