package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountRepository;
import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.OperationEventService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountPurgeSchedulerTest {

    private AccountRepository accounts;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private OperationEventService observability;
    private Any2ApiProperties properties;
    private AccountPurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        jdbc = mock(JdbcClient.class);
        var txManager = mock(PlatformTransactionManager.class);
        transactions = new TransactionTemplate(txManager) {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
            }
        };
        observability = mock(OperationEventService.class);
        properties = new Any2ApiProperties();
        properties.getLifecycle().getAccountPurge().setEnabled(true);
        properties.getLifecycle().getAccountPurge().setRetentionPeriod(Duration.ofHours(72));
        properties.getLifecycle().getAccountPurge().setFailureThreshold(5);
        properties.getLifecycle().getAccountPurge().setBatchSize(50);

        scheduler = new AccountPurgeScheduler(
            accounts, jdbc, transactions, observability, properties
        );
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.getLifecycle().getAccountPurge().setEnabled(false);

        var result = scheduler.purgeDeadAccounts();

        assertThat(result).isZero();
        verify(accounts, never()).findDeadAccountIds(any(), anyInt(), anyInt());
    }

    @Test
    void doesNothingWhenNoDeadAccountsFound() {
        when(accounts.findDeadAccountIds(any(), eq(5), eq(50))).thenReturn(List.of());

        var result = scheduler.purgeDeadAccounts();

        assertThat(result).isZero();
        verify(accounts, never()).deleteAllById(any());
    }

    @Test
    void purgesDeadAccountsAndUnlinksAssociations() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var deadIds = List.of(id1, id2);

        when(accounts.findDeadAccountIds(any(), eq(5), eq(50))).thenReturn(deadIds);

        var specMock = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(specMock);
        when(specMock.param(any(String.class), any())).thenReturn(specMock);
        when(specMock.update()).thenReturn(1);

        var started = mock(OperationEventService.Started.class);
        when(observability.start(any(), any(), any(), any())).thenReturn(started);

        var result = scheduler.purgeDeadAccounts();

        assertThat(result).isEqualTo(2);
        verify(accounts).deleteAllById(eq(deadIds));
        verify(observability).start(eq("LIFECYCLE"), eq("system"), eq("purge_dead_accounts"), any());
        verify(observability).succeed(eq(started), eq("purged_2"));
    }
}
