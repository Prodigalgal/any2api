package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import java.util.List;
import java.util.Map;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogCacheTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void currentAdapterContractReplacesStaleDiscoveryWhileKeepingLimitsAndDiscoveryEvidence() throws Exception {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = (JdbcClient.MappedQuerySpec<ModelCatalogCache.Entry>) mock(JdbcClient.MappedQuerySpec.class);
        var mapping = new AtomicReference<RowMapper<ModelCatalogCache.Entry>>();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            mapping.set(invocation.getArgument(0));
            return query;
        });
        var row = mock(ResultSet.class);
        when(row.getString("provider_id")).thenReturn("mimo");
        when(row.getString("upstream_id")).thenReturn("fixture");
        when(row.getString("display_name")).thenReturn("Fixture");
        when(row.getString("metadata")).thenReturn("{\"revision\":2}");
        when(row.getString("discovered_capabilities"))
            .thenReturn("{\"supported_parameters\":{\"responses\":[\"old_control\"]},\"max_input_tokens\":70000}");
        when(row.getObject("max_input_tokens_override")).thenReturn(60000L);
        var roles = mock(java.sql.Array.class);
        when(roles.getArray()).thenReturn(new String[0]);
        when(row.getArray("random_roles")).thenReturn(roles);
        when(query.list()).thenAnswer(ignored -> List.of(mapping.get().mapRow(row, 0)));
        var redis = mock(ReactiveStringRedisTemplate.class);
        var values = (ReactiveValueOperations<String, String>) mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        when(values.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        var providers = mock(ProviderRegistry.class);
        var provider = mock(InferenceProvider.class);
        when(providers.requirePlugin("mimo")).thenReturn(provider);
        var contract = mock(ModelCapabilityContract.class);
        when(provider.modelContract(any())).thenReturn(contract);
        when(contract.asMap()).thenReturn(Map.of(
            "supported_parameters", Map.of("responses", List.of("top_p")),
            "parameter_adaptation", Map.of("top_p", Map.of("target", "modelConfig.topP"))));
        var cache = new ModelCatalogCache(jdbc, new ObjectMapper(), redis, new Any2ApiProperties(), providers);

        var entry = cache.list().block().getFirst();
        assertThat(entry.capabilities().path("supported_parameters").path("responses").get(0).asText())
            .isEqualTo("top_p");
        assertThat(entry.capabilities().path("parameter_adaptation").path("top_p").path("target").asText())
            .isEqualTo("modelConfig.topP");
        assertThat(entry.capabilities().path("max_input_tokens").asLong()).isEqualTo(60000L);
        assertThat(entry.discoveredCapabilities().path("max_input_tokens").asLong()).isEqualTo(70000L);
        assertThat(entry.discoveredCapabilities().path("supported_parameters").path("responses").get(0).asText())
            .isEqualTo("old_control");
        verify(provider).modelContract(new DiscoveredModel("fixture", "Fixture", Map.of("revision", 2)));
    }

    @Test
    void concurrentReadsShareOneDecodedSnapshotAndEvictionRefreshesMetadata() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just(snapshot(1)), Mono.just(snapshot(2)));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));
        var mapper = spy(new ObjectMapper());
        var cache = new ModelCatalogCache(mock(JdbcClient.class), mapper, redis,
            new Any2ApiProperties(), mock(ProviderRegistry.class));

        var reads = Flux.range(0, 8).flatMap(ignored -> cache.list(), 8).collectList().block();
        assertThat(reads).hasSize(8).allSatisfy(entries -> {
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().metadata().path("revision").asInt()).isEqualTo(1);
            assertThat(entries).isSameAs(reads.getFirst());
        });
        assertThat(cache.find("mimo", "fixture").block()).isPresent();
        verify(mapper, times(1)).readValue(anyString(), any(JavaType.class));

        cache.invalidate().block();
        var refreshed = cache.list().block();
        assertThat(refreshed).isNotSameAs(reads.getFirst());
        assertThat(refreshed.getFirst().metadata().path("revision").asInt()).isEqualTo(2);
        verify(mapper, times(2)).readValue(anyString(), any(JavaType.class));
    }

    private String snapshot(int revision) {
        var model = new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
            "id", "fixture", "providerId", "mimo", "metadata", java.util.Map.of("revision", revision),
            "capabilities", java.util.Map.of(), "randomRoles", List.of()));
        for (var field : List.of("created", "eligibleAccountCount", "availableAccountCount",
            "quotaLimitedAccountCount", "rollingRequestCount", "rollingAttemptCount", "p50Ms", "p95Ms")) {
            model.put(field, 0);
        }
        model.put("available", true);
        model.put("rollingSuccessRate", 1.0);
        return new ObjectMapper().writeValueAsString(List.of(model));
    }
}
