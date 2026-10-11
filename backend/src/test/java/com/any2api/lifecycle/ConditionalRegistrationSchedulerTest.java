package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountRepository;
import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ConditionalRegistrationSchedulerTest {

    private AccountRepository accounts;
    private RegistrationJobService registrationJobs;
    private ProviderRegistry providers;
    private AutomationProviderCatalog automationProviders;
    private JdbcClient jdbc;
    private Any2ApiProperties properties;
    private ConditionalRegistrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        registrationJobs = mock(RegistrationJobService.class);
        providers = mock(ProviderRegistry.class);
        automationProviders = mock(AutomationProviderCatalog.class);
        jdbc = mock(JdbcClient.class);
        properties = new Any2ApiProperties();
        properties.getLifecycle().getConditionalRegistration().setEnabled(true);
        properties.getLifecycle().getConditionalRegistration().setMinActiveAccounts(50);
        properties.getLifecycle().getConditionalRegistration().setBatchTarget(5);
        properties.getLifecycle().getConditionalRegistration().setCooldown(Duration.ofMinutes(10));

        scheduler = new ConditionalRegistrationScheduler(
            accounts, registrationJobs, providers, automationProviders, jdbc, properties
        );
    }

    @Test
    void doesNotTriggerWhenAccountsAreSufficient() {
        var now = Instant.now();
        when(accounts.countActiveByProviderId(eq("qwen"), any())).thenReturn(55L);

        scheduler.evaluateProvider("qwen", properties.getLifecycle().getConditionalRegistration(), now);

        verify(registrationJobs, never()).create(any());
    }

    @Test
    void triggersWhenAccountsBelowThresholdAndNoInFlightJobs() {
        var now = Instant.now();
        when(accounts.countActiveByProviderId(eq("qwen"), any())).thenReturn(12L);

        var specMock = mock(JdbcClient.StatementSpec.class);
        var mappedQueryMock = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(specMock);
        when(specMock.param(any(String.class), any())).thenReturn(specMock);
        when(specMock.query(eq(Integer.class))).thenReturn(mappedQueryMock);
        when(mappedQueryMock.single()).thenReturn(0);

        scheduler.evaluateProvider("qwen", properties.getLifecycle().getConditionalRegistration(), now);

        var captor = ArgumentCaptor.forClass(RegistrationJobService.CreateCommand.class);
        verify(registrationJobs).create(captor.capture());
        var command = captor.getValue();
        assertThat(command.providerId()).isEqualTo("qwen");
        assertThat(command.target()).isEqualTo(5);
        assertThat(command.idempotencyKey()).startsWith("conditional-reg:qwen:");
    }

    @Test
    void skipsWhenInFlightJobExists() {
        var now = Instant.now();
        when(accounts.countActiveByProviderId(eq("qwen"), any())).thenReturn(5L);

        var specMock = mock(JdbcClient.StatementSpec.class);
        var mappedQueryMock = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(specMock);
        when(specMock.param(any(String.class), any())).thenReturn(specMock);
        when(specMock.query(eq(Integer.class))).thenReturn(mappedQueryMock);
        when(mappedQueryMock.single()).thenReturn(1); // 1 in flight job

        scheduler.evaluateProvider("qwen", properties.getLifecycle().getConditionalRegistration(), now);

        verify(registrationJobs, never()).create(any());
            verify(registrationJobs, never()).create(any());
    }

    @Test
    void skipsWhenTriggeredWithin24HoursCooldown() {
        var now = Instant.now();
        properties.getLifecycle().getConditionalRegistration().setCooldown(Duration.ofHours(24));
        when(accounts.countActiveByProviderId(eq("arena"), any())).thenReturn(5L);

        var specMock = mock(JdbcClient.StatementSpec.class);
        var mappedQueryMock = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(specMock);
        when(specMock.param(any(String.class), any())).thenReturn(specMock);
        when(specMock.query(eq(Integer.class))).thenReturn(mappedQueryMock);
        when(mappedQueryMock.single()).thenReturn(0);

        // First trigger succeeds
        scheduler.evaluateProvider("arena", properties.getLifecycle().getConditionalRegistration(), now);
        verify(registrationJobs).create(any());

        // Second trigger 5 hours later (still within 24h cooldown) should be suppressed by memory cache
        scheduler.evaluateProvider("arena", properties.getLifecycle().getConditionalRegistration(), now.plus(Duration.ofHours(5)));
        // Still called only once
        verify(registrationJobs).create(any());
    }

    @Test
    void skipsWhenDatabaseHasRecentConditionalJobWithin24Hours() {
        var now = Instant.now();
        properties.getLifecycle().getConditionalRegistration().setCooldown(Duration.ofHours(24));
        when(accounts.countActiveByProviderId(eq("deepseek"), any())).thenReturn(5L);

        var specMock = mock(JdbcClient.StatementSpec.class);
        var mappedQueryMock = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(specMock);
        when(specMock.param(any(String.class), any())).thenReturn(specMock);
        when(specMock.query(eq(Integer.class))).thenReturn(mappedQueryMock);
        // First query (inFlight) -> 0; Second query (recentJobs in 24h) -> 1
        when(mappedQueryMock.single()).thenReturn(0, 1);

        scheduler.evaluateProvider("deepseek", properties.getLifecycle().getConditionalRegistration(), now);

        verify(registrationJobs, never()).create(any());
    }
}
