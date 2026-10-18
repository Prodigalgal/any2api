package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.config.Any2ApiProperties;
import com.any2api.coordination.AccountLeaseService;
import com.any2api.credential.CredentialVault;
import com.any2api.interop.InteropDatabase;
import com.any2api.observability.OperationEventService;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderProtocolContract;
import com.any2api.provider.ProviderRegistry;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.settings.RuntimeSettingsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class LifecycleSchedulingIntegrationTest {
    @Test
    void claimsOnlyExecutableSlotsAndDoesNotFillTheWholeBatchWithOneProvider() throws Exception {
        try (var database = new InteropDatabase()) {
            var transactions = transactions(database);
            var scheduler = scheduler(database, transactions);
            for (var provider : List.of("arena", "arena", "arena", "mimo", "glm")) {
                seedAction(database, provider, UUID.randomUUID());
            }
            List<?> claimed = transactions.execute(ignored -> ReflectionTestUtils.invokeMethod(
                scheduler, "claim", "test-owner"));
            assertThat(claimed).hasSize(2);
            assertThat(database.jdbc.sql("SELECT COUNT(DISTINCT provider_id) FROM scheduled_actions WHERE status='LEASED'")
                .query(Integer.class).single()).isEqualTo(2);
            assertThat(database.jdbc.sql("SELECT COUNT(*) FROM scheduled_actions WHERE status='PENDING'")
                .query(Integer.class).single()).isEqualTo(3);
        }
    }

    @Test
    void onlyDefersWithCompletedInitialCheckAndSuccessfulOrdinaryInference() throws Exception {
        try (var database = new InteropDatabase()) {
            var transactions = transactions(database);
            var scheduler = scheduler(database, transactions);
            var account = AccountEntity.create("mimo", "fixture", null, null, Map.of());
            account.markUsed(Instant.now().minusSeconds(60));
            database.jdbc.sql("INSERT INTO accounts(id,provider_id,external_id,status) VALUES(:id,'mimo','fixture','ACTIVE')")
                .param("id", account.getId()).update();
            seedAction(database, "mimo", account.getId());
            List<?> claimed = transactions.execute(ignored -> ReflectionTestUtils.invokeMethod(
                scheduler, "claim", "test-owner"));
            var action = claimed.getFirst();
            var event = UUID.randomUUID();
            database.jdbc.sql("""
                INSERT INTO usage_events(id,request_id,provider_id,account_id,model_id,protocol,
                    success,request_kind,created_at,duration_ms)
                VALUES(:id,:request,'mimo',:account,'basic','CHAT_COMPLETIONS',false,
                    'INFERENCE',CURRENT_TIMESTAMP-INTERVAL '1 minute',1000)
                """).param("id", event).param("request", event.toString()).param("account", account.getId()).update();
            assertThat(defer(scheduler, action, account)).isFalse();
            database.jdbc.sql("UPDATE usage_events SET success=true WHERE id=:id").param("id", event).update();
            assertThat(defer(scheduler, action, account)).isFalse();
            database.jdbc.sql("""
                INSERT INTO account_inference_checks(account_id,claim_token,status,source)
                VALUES(:account,:token,'READY','INITIAL')
                """).param("account", account.getId()).param("token", UUID.randomUUID()).update();
            database.jdbc.sql("UPDATE usage_events SET request_kind='PROBE' WHERE id=:id").param("id", event).update();
            assertThat(defer(scheduler, action, account)).isFalse();
            database.jdbc.sql("UPDATE usage_events SET request_kind='INFERENCE' WHERE id=:id").param("id", event).update();
            assertThat(defer(scheduler, action, account)).isTrue();
            assertThat(database.jdbc.sql("""
                SELECT COUNT(*) FROM scheduled_actions WHERE status='PENDING'
                  AND due_at>CURRENT_TIMESTAMP AND lease_owner IS NULL
                  AND last_error_class='KeepaliveDeferredRecentInference'
                """).query(Integer.class).single()).isEqualTo(1);
            assertThatThrownBy(() -> defer(scheduler, action, account))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease was lost");
        }
    }

    private boolean defer(LifecycleScheduler scheduler, Object action, AccountEntity account) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
            scheduler, "deferRecentlyUsedKeepalive", action, "test-owner", account));
    }

    private void seedAction(InteropDatabase database, String provider, UUID account) {
        var id = UUID.randomUUID();
        database.jdbc.sql("""
            INSERT INTO scheduled_actions(id,handler,provider_id,entity_type,entity_id,
                action_family,due_at,generation,status,idempotency_key)
            VALUES(:id,'provider-automation',:provider,'ACCOUNT',:account,'keepalive',
                CURRENT_TIMESTAMP-INTERVAL '1 hour',1,'PENDING',:key)
            """).param("id", id).param("provider", provider).param("account", account.toString())
            .param("key", id.toString()).update();
    }

    private TransactionTemplate transactions(InteropDatabase database) {
        return new TransactionTemplate(new DataSourceTransactionManager(database.dataSource));
    }

    private LifecycleScheduler scheduler(InteropDatabase database, TransactionTemplate transactions) {
        var provider = mock(InferenceProvider.class);
        when(provider.manifest()).thenReturn(new ProviderManifest("mimo", "MiMo", "test", "1",
            List.of(), Map.of(com.any2api.provider.ProviderCapability.CHAT_COMPLETIONS,
                com.any2api.provider.SupportLevel.NATIVE,
                com.any2api.provider.ProviderCapability.RESPONSES,
                com.any2api.provider.SupportLevel.NATIVE), true));
        when(provider.protocolContract()).thenReturn(ProviderProtocolContract.strict());
        var settings = mock(RuntimeSettingsService.class);
        when(settings.keepalivePolicy("mimo")).thenReturn(RuntimeSettingsService.ProviderKeepalivePolicy.standard());
        return new LifecycleScheduler(database.jdbc, transactions, mock(AccountRepository.class),
            mock(CredentialVault.class), mock(AccountLeaseService.class),
            ProviderRegistry.allEnabled(List.of(provider)), mock(ProxyPoolService.class),
            mock(LifecycleOperationExecutor.class), mock(InferenceReadinessProbe.class),
            new ObjectMapper(), mock(OperationEventService.class), settings, List.of(),
            mock(InitialInferenceCheckService.class), new Any2ApiProperties());
    }
}
