package com.any2api.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.any2api.config.Any2ApiProperties;
import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.credential.SecretCipher;
import com.any2api.interop.InteropDatabase;
import com.any2api.provider.ProviderInstallationCatalog;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.settings.RuntimeSettingsService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminReadBatchIntegrationTest {
    private InteropDatabase database;
    private JdbcClient jdbc;
    private SecretCipher cipher;
    private ProxyPoolService pools;
    private RuntimeSettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void start() throws Exception {
        database = new InteropDatabase();
    }

    @AfterAll
    void stop() throws Exception {
        if (database != null) database.close();
    }

    @BeforeEach
    void prepare() {
        database.jdbc.sql("DELETE FROM provider_proxy_bindings").update();
        database.jdbc.sql("DELETE FROM proxy_pools").update();
        database.jdbc.sql("DELETE FROM system_settings").update();
        var properties = new Any2ApiProperties();
        properties.getSecurity().setCredentialMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.getTempMail().setApiBase("https://mail.example.test/");
        properties.getTempMail().setDomains(List.of("Domain.Example", " Domain.Example "));
        cipher = spy(new SecretCipher(properties));
        jdbc = spy(database.jdbc);
        pools = new ProxyPoolService(jdbc, new PostgresAdvisoryLocks(jdbc),
            mock(ProviderInstallationCatalog.class), cipher, mapper);
        settings = new RuntimeSettingsService(jdbc, mapper, cipher, properties);
    }

    @Test
    void emptyPoolsDoNotQueryBindings() {
        assertThat(pools.list()).isEmpty();
        verify(jdbc, times(1)).sql(anyString());
    }

    @Test
    void poolBindingsStayIsolatedAndUnboundOrDisabledPoolsRemainVisible() {
        var first = pool("Alpha", true, 2);
        var second = pool("Bravo", false, 3);
        var unbound = pool("Charlie", true, 0);
        bind(first, "mimo", List.of("REGISTRATION", "INFERENCE"));
        bind(first, "longcat", List.of("REGISTRATION"));
        bind(second, "grok_web", List.of("INFERENCE"));

        var views = pools.list();

        assertThat(views).extracting(ProxyPoolService.ProxyPoolView::name)
            .containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(views.getFirst().bindingScopes()).isEqualTo(Map.of(
            "mimo", List.of("REGISTRATION", "INFERENCE"), "longcat", List.of("REGISTRATION")));
        assertThat(views.getFirst().providerIds()).containsExactlyInAnyOrder("mimo", "longcat");
        assertThat(views.getFirst().nodeCount()).isEqualTo(2);
        assertThat(views.get(1).enabled()).isFalse();
        assertThat(views.get(1).bindingScopes()).containsOnlyKeys("grok_web");
        assertThat(views.getLast().id()).isEqualTo(unbound);
        assertThat(views.getLast().providerIds()).isEmpty();
        assertThat(views.getLast().bindingScopes()).isEmpty();
        assertThat(views).allSatisfy(view -> {
            assertThat(view.sourceConfigured()).isTrue();
            assertThat(view.createdAt()).isNotNull();
        });
        verify(jdbc, times(2)).sql(anyString());
        verify(cipher, never()).open(any(), any(), anyString());
    }

    @Test
    void largePoolListsUseBoundedBatchesInsteadOfPerPoolQueries() {
        database.jdbc.sql("""
            INSERT INTO proxy_pools(id, name, mode, encrypted_payload, nonce)
            SELECT gen_random_uuid(), 'pool-' || lpad(sequence::text, 4, '0'),
                'NODE_LIST', decode('01', 'hex'), decode('00', 'hex')
            FROM generate_series(1,501) AS sequence
            """).update();

        var views = pools.list();

        assertThat(views).hasSize(501);
        assertThat(views).extracting(ProxyPoolService.ProxyPoolView::name).isSorted();
        verify(jdbc, times(3)).sql(anyString());
    }

    @Test
    void missingSettingsKeepNormalizedDefaultsInOneRead() {
        var view = settings.get();

        assertThat(view.tempMail().apiBase()).isEqualTo("https://mail.example.test");
        assertThat(view.tempMail().domains()).containsExactly("domain.example");
        assertThat(view.registrationDefaults()).isEqualTo(RuntimeSettingsService.RegistrationDefaults.standard());
        assertThat(view.providerKeepalive()).isEqualTo(RuntimeSettingsService.ProviderKeepaliveSettings.standard());
        verify(jdbc, times(1)).sql(anyString());
    }

    @Test
    void batchedSettingsMatchIndividualReadsAndReflectUpdatesImmediately() {
        settings.updateTempMail(new RuntimeSettingsService.TempMailSettings(
            "https://configured.example.test/", "fixture-admin", "fixture-site", List.of("MAIL.EXAMPLE"), 5, 300, 20));
        settings.updateRegistrationDefaults(RuntimeSettingsService.RegistrationDefaults.standard());
        settings.updateProviderKeepalive(new RuntimeSettingsService.ProviderKeepaliveSettings(Map.of(
            "mimo", new RuntimeSettingsService.ProviderKeepalivePolicy(30, 3, Map.of("locale", "en")))));
        var expected = new RuntimeSettingsService.SettingsView(
            settings.tempMail(), settings.registrationDefaults(), settings.providerKeepalive());
        clearInvocations(jdbc);

        assertThat(settings.get()).isEqualTo(expected);
        verify(jdbc, times(1)).sql(anyString());
        settings.updateProviderKeepalive(new RuntimeSettingsService.ProviderKeepaliveSettings(Map.of()));
        assertThat(settings.get().providerKeepalive().providers()).isEmpty();
    }

    @Test
    void corruptEncryptedSettingsFailInsteadOfFallingBackSilently() {
        saveRawMail(new RuntimeSettingsService.TempMailSettings("", "", "", List.of(), 5, 300, 20));
        database.jdbc.sql("UPDATE system_settings SET encrypted_value=decode('00','hex')").update();

        assertThatThrownBy(settings::get).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("secret encryption failed");
    }

    @Test
    void savedInvalidSettingsStillFailNormalization() {
        saveRawMail(new RuntimeSettingsService.TempMailSettings("", "", "", List.of(), 0, 300, 20));

        assertThatThrownBy(settings::get).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mail poll interval");
    }

    private UUID pool(String name, boolean enabled, int nodes) {
        var id = UUID.randomUUID();
        database.jdbc.sql("""
            INSERT INTO proxy_pools(id,name,mode,enabled,encrypted_payload,nonce,node_count)
            VALUES(:id,:name,'NODE_LIST',:enabled,decode('01','hex'),decode('00','hex'),:nodes)
            """).param("id", id).param("name", name).param("enabled", enabled).param("nodes", nodes).update();
        return id;
    }

    private void bind(UUID pool, String provider, List<String> scopes) {
        database.jdbc.sql("""
            INSERT INTO provider_proxy_bindings(proxy_pool_id,provider_id,traffic_scopes)
            VALUES(:pool,:provider,:scopes)
            """).param("pool", pool).param("provider", provider)
            .param("scopes", scopes.toArray(String[]::new)).update();
    }

    private void saveRawMail(RuntimeSettingsService.TempMailSettings value) {
        var sealed = cipher.seal(mapper.writeValueAsBytes(value), "any2api:system-setting:TEMP_MAIL");
        database.jdbc.sql("""
            INSERT INTO system_settings(setting_key,encrypted_value,nonce,key_version)
            VALUES('TEMP_MAIL',:encrypted,:nonce,1)
            """).param("encrypted", sealed.encrypted()).param("nonce", sealed.nonce()).update();
    }
}
