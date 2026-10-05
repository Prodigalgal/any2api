package com.any2api.provider.grok_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import com.any2api.interop.InteropDatabase;
import com.any2api.protocol.state.ProviderResponseStateStore;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class GrokWebCatalogEligibilityIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void basicPoolCannotAdvertisePaidModesEvenWithFreshReadyProbes() throws Exception {
        try (var database = new InteropDatabase()) {
            seedModels(database);
            account(database, "basic", "basic", "ACTIVE", true);
            var nullable = account(database, "nullable-basic", null, "ACTIVE", true);
            database.jdbc.sql("UPDATE accounts SET metadata='{\"tier\":null,\"optional\":null}'::jsonb WHERE id=:id")
                .param("id", nullable).update();
            account(database, "disabled-super", "super", "ACTIVE", false);
            account(database, "expired-heavy", "heavy", "EXPIRED", true);
            var expired = account(database, "elapsed-heavy", "heavy", "ACTIVE", true);
            database.jdbc.sql("UPDATE accounts SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=:id")
                .param("id", expired).update();
            var cooled = account(database, "cooled-heavy", "heavy", "ACTIVE", true);
            database.jdbc.sql("UPDATE accounts SET cooldown_until=CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id=:id")
                .param("id", cooled).update();
            var jdbc = spy(database.jdbc);
            var cache = cache(jdbc, List.of(grok(), mimo()));

            var entries = cache.list().block();

            assertThat(entry(entries, "grok-3").eligibleAccountCount()).isEqualTo(2);
            assertThat(entry(entries, "grok-imagine-image").availableAccountCount()).isEqualTo(2);
            for (var model : List.of("grok-chat-auto", "grok-chat-expert", "grok-chat-heavy",
                    "grok-3-deepsearch", "grok-imagine-image-quality", "unknown-mode")) {
                var entry = entry(entries, model);
                assertThat(entry.eligibleAccountCount()).as(model).isZero();
                assertThat(entry.availableAccountCount()).as(model).isZero();
                assertThat(entry.quotaLimitedAccountCount()).as(model).isZero();
                assertThat(entry.available()).as(model).isFalse();
                assertThat(entry.runtimeStatus()).as(model).isEqualTo("UNAVAILABLE");
                assertThat(entry.probeStatus()).as(model).isEqualTo("READY");
            }
            assertThat(cache.list().block()).isSameAs(entries);
            // No eligible-account cooldown query is issued per model or per account.
            verify(jdbc, times(3)).sql(anyString());
            database.jdbc.sql("UPDATE accounts SET enabled=FALSE WHERE provider_id='grok_web'").update();
            cache.invalidate().block();
            var unavailable = cache.list().block();
            assertThat(entry(unavailable, "grok-3").eligibleAccountCount()).isZero();
            assertThat(entry(unavailable, "grok-3").available()).isFalse();
            assertThat(entry(unavailable, "grok-3").runtimeStatus()).isEqualTo("UNAVAILABLE");
            // With no eligible restricted accounts the cooldown batch is omitted.
            verify(jdbc, times(5)).sql(anyString());
        }
    }

    @Test
    void mixedTiersCountOnlyQualifiedCooldownsAndKeepUnrestrictedProviders() throws Exception {
        try (var database = new InteropDatabase()) {
            seedModels(database);
            var basic = account(database, "basic", null, "ACTIVE", true);
            var unknown = account(database, "unknown", "unknown", "ACTIVE", true);
            var paid = account(database, "paid", "paid", "DEGRADED", true);
            var heavy = account(database, "heavy", "heavy", "ACTIVE", true);
            database.jdbc.sql("INSERT INTO accounts(id,provider_id,external_id,status,enabled) VALUES(gen_random_uuid(),'mimo','fixture','ACTIVE',TRUE)")
                .update();
            cooldown(database, basic, "grok-chat-auto", "quota exhausted", true);
            cooldown(database, paid, "grok-chat-auto", "quota exhausted", true);
            cooldown(database, heavy, "grok-chat-auto", "upstream failure", true);
            cooldown(database, heavy, "grok-chat-heavy", "quota exhausted", true);
            cooldown(database, heavy, "grok-3-deepsearch", "upstream failure", true);
            cooldown(database, unknown, "grok-3", "quota exhausted", false);
            var jdbc = spy(database.jdbc);
            var cache = cache(jdbc, List.of(grok(), mimo()));

            var entries = cache.list().block();

            var fast = entry(entries, "grok-3");
            assertThat(fast.eligibleAccountCount()).isEqualTo(4);
            assertThat(fast.availableAccountCount()).isEqualTo(4);
            var auto = entry(entries, "grok-chat-auto");
            assertThat(auto.eligibleAccountCount()).isEqualTo(2);
            assertThat(auto.availableAccountCount()).isZero();
            assertThat(auto.quotaLimitedAccountCount()).isEqualTo(1);
            assertThat(auto.available()).isFalse();
            assertThat(auto.runtimeStatus()).isEqualTo("UNAVAILABLE");
            var heavyEntry = entry(entries, "grok-chat-heavy");
            assertThat(heavyEntry.eligibleAccountCount()).isEqualTo(1);
            assertThat(heavyEntry.quotaLimitedAccountCount()).isEqualTo(1);
            var alias = entry(entries, "grok-3-deepsearch");
            assertThat(alias.eligibleAccountCount()).isEqualTo(1);
            assertThat(alias.availableAccountCount()).isZero();
            assertThat(alias.quotaLimitedAccountCount()).isZero();
            assertThat(entry(entries, "grok-imagine-image-quality").availableAccountCount()).isEqualTo(2);
            var unrestricted = entry(entries, "fixture");
            assertThat(unrestricted.providerId()).isEqualTo("mimo");
            assertThat(unrestricted.eligibleAccountCount()).isEqualTo(1);
            assertThat(unrestricted.availableAccountCount()).isEqualTo(1);
            assertThat(unrestricted.available()).isTrue();
            cache.list().block();
            verify(jdbc, times(3)).sql(anyString());
        }
    }

    @Test
    void staticPolicyAndRoutingShareRulesWithoutMutatingNullableMetadata() {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("tier", null);
        metadata.put("optional", null);
        var profile = new ProviderAccountProfile(UUID.randomUUID(), metadata);
        metadata.put("tier", "heavy");
        assertThat(profile.metadata()).containsEntry("tier", null).containsEntry("optional", null);
        assertThatThrownBy(() -> profile.metadata().put("tier", "heavy"))
            .isInstanceOf(UnsupportedOperationException.class);
        var provider = grok();
        assertThat(provider.modelAccountPolicy().orElseThrow().supports("grok-3", profile)).isTrue();
        assertThat(provider.modelAccountPolicy().orElseThrow().supports("grok-chat-heavy", profile)).isFalse();
        for (var model : List.of("grok-3", "grok-chat-heavy", "unknown-mode")) {
            var request = new CanonicalRequest("fixture", CanonicalRequest.Protocol.CHAT_COMPLETIONS,
                "grok_web", model, false, List.of(mapper.createObjectNode().put("role", "user").put("content", "hello")),
                Map.of(), Map.of(), List.of(), Map.of(), mapper.createObjectNode());
            assertThat(provider.supportsAccount(request, profile)).as(model).isEqualTo(model.equals("grok-3"));
        }
    }

    @Test
    void catalogWithoutAccountPolicyKeepsSingleQueryLoading() throws Exception {
        try (var database = new InteropDatabase()) {
            seedModels(database);
            database.jdbc.sql("UPDATE providers SET enabled=FALSE WHERE id='grok_web'").update();
            var jdbc = spy(database.jdbc);
            var cache = cache(jdbc, List.of(mimo()));

            var entries = cache.list().block();

            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().eligibleAccountCount()).isZero();
            assertThat(entries.getFirst().available()).isFalse();
            cache.list().block();
            verify(jdbc, times(1)).sql(anyString());
        }
    }

    private void seedModels(InteropDatabase database) {
        database.jdbc.sql("UPDATE providers SET installed=TRUE,enabled=TRUE WHERE id IN ('grok_web','mimo')").update();
        for (var model : List.of("grok-3", "grok-chat-auto", "grok-chat-expert", "grok-chat-heavy",
                "grok-3-deepsearch", "grok-imagine-image", "grok-imagine-image-quality", "unknown-mode")) {
            database.jdbc.sql("INSERT INTO models(id,provider_id,upstream_id,enabled) VALUES(:id,'grok_web',:model,TRUE)")
                .param("id", "grok_web/" + model).param("model", model).update();
        }
        database.jdbc.sql("INSERT INTO models(id,provider_id,upstream_id,enabled) VALUES('mimo/fixture','mimo','fixture',TRUE)").update();
        database.jdbc.sql("INSERT INTO model_probe_results(provider_id,model_id,status) SELECT provider_id,upstream_id,'READY' FROM models").update();
    }

    private UUID account(InteropDatabase database, String name, String tier, String status, boolean enabled) {
        var id = UUID.randomUUID();
        database.jdbc.sql("INSERT INTO accounts(id,provider_id,external_id,status,enabled,metadata) VALUES(:id,'grok_web',:name,:status,:enabled,CAST(:metadata AS JSONB))")
            .param("id", id).param("name", name).param("status", status).param("enabled", enabled)
            .param("metadata", mapper.writeValueAsString(tier == null ? Map.of() : Map.of("tier", tier))).update();
        return id;
    }

    private void cooldown(InteropDatabase database, UUID account, String model, String reason, boolean active) {
        database.jdbc.sql("INSERT INTO account_model_cooldowns(account_id,provider_id,model_id,reason,cooldown_until) VALUES(:account,'grok_web',:model,:reason,CURRENT_TIMESTAMP + (:seconds * INTERVAL '1 second'))")
            .param("account", account).param("model", model).param("reason", reason).param("seconds", active ? 3600 : -3600).update();
    }

    private ModelCatalogCache.Entry entry(List<ModelCatalogCache.Entry> entries, String model) {
        return entries.stream().filter(entry -> entry.id().equals(model)).findFirst().orElseThrow();
    }

    private GrokWebProvider grok() {
        return new GrokWebProvider(mock(OfficialBrowserTransportClient.class), mock(OfficialBrowserSemanticCommandFactory.class),
            mock(ProxyPoolService.class), mock(ProviderResponseStateStore.class), mock(ExecutorService.class),
            new GrokWebRequestMapper(mapper, new GrokWebToolProtocol(mapper)), mapper, new GrokWebFailureClassifier());
    }

    private InferenceProvider mimo() {
        var provider = mock(InferenceProvider.class, CALLS_REAL_METHODS);
        when(provider.manifest()).thenReturn(new ProviderManifest("mimo", "MiMo", "test", "1", List.of("fixture"),
            Map.of(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE, ProviderCapability.RESPONSES, SupportLevel.NATIVE), true));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ModelCatalogCache cache(JdbcClient jdbc, List<InferenceProvider> plugins) {
        var redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        when(values.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));
        var registry = mock(ProviderRegistry.class);
        when(registry.plugins()).thenReturn(plugins);
        for (var plugin : plugins) when(registry.requirePlugin(plugin.manifest().id())).thenReturn(plugin);
        return new ModelCatalogCache(jdbc, mapper, redis, new Any2ApiProperties(), registry);
    }
}
