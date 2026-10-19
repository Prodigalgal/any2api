package com.any2api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.coordination.PostgresAdvisoryLocks;
import com.any2api.interop.InteropDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OperationReconciliationSchedulerTest {
    @Test
    void preservesCurrentOwnershipAndClosesOnlyAbandonedOldOperations() throws Exception {
        try (var database = new InteropDatabase()) {
            var jdbc = database.jdbc;
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(database.dataSource));
            var scheduler = new OperationReconciliationScheduler(jdbc, new PostgresAdvisoryLocks(jdbc));
            var account = UUID.randomUUID().toString();
            jdbc.sql("""
                INSERT INTO scheduled_actions(id,handler,provider_id,entity_type,entity_id,
                    action_family,due_at,generation,status,idempotency_key,lease_owner,
                    lease_expires_at,updated_at)
                VALUES(:id,'provider-automation','mimo','ACCOUNT',:account,'keepalive',
                    CURRENT_TIMESTAMP,1,'LEASED',:account,'owner',
                    CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP-INTERVAL '3 hours')
                """).param("id", UUID.randomUUID()).param("account", account).update();
            var ownedLifecycle = event(jdbc, "LIFECYCLE", account, 1, "2 hours");
            var orphan = event(jdbc, "LIFECYCLE", UUID.randomUUID().toString(), 1, "2 hours");
            var recent = event(jdbc, "LIFECYCLE", UUID.randomUUID().toString(), 1, "1 minute");
            var inference = event(jdbc, "INFERENCE", UUID.randomUUID().toString(), 1, "2 hours");
            var job = UUID.randomUUID();
            jdbc.sql("""
                INSERT INTO registration_jobs(id,provider_id,status,idempotency_key,attempts,
                    concurrency,lease_owner,lease_expires_at)
                VALUES(:id,'mimo','RUNNING',:key,5,2,'owner',CURRENT_TIMESTAMP+INTERVAL '5 minutes')
                """).param("id", job).param("key", job.toString()).update();
            var ownedRegistration = event(jdbc, "REGISTRATION", job.toString(), 6, "40 minutes");
            var olderAttempt = event(jdbc, "REGISTRATION", job.toString(), 5, "2 hours");
            var missingJob = event(jdbc, "REGISTRATION", UUID.randomUUID().toString(), 1, "2 hours");

            Integer firstPass = transactions.execute(ignored -> scheduler.reconcile());
            assertThat(firstPass).isEqualTo(3);
            for (var id : java.util.List.of(orphan, olderAttempt, missingJob)) {
                assertThat(status(jdbc, id)).isEqualTo("FAILED");
            }
            for (var id : java.util.List.of(ownedLifecycle, ownedRegistration, recent, inference)) {
                assertThat(status(jdbc, id)).isEqualTo("RUNNING");
            }
            Integer repeated = transactions.execute(ignored -> scheduler.reconcile());
            assertThat(repeated).isZero();

            jdbc.sql("UPDATE scheduled_actions SET updated_at=CURRENT_TIMESTAMP").update();
            jdbc.sql("UPDATE registration_jobs SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'").update();
            Integer expired = transactions.execute(ignored -> scheduler.reconcile());
            assertThat(expired).isEqualTo(2);
            assertThat(status(jdbc, ownedLifecycle)).isEqualTo("FAILED");
            assertThat(status(jdbc, ownedRegistration)).isEqualTo("FAILED");
        }
    }

    @Test
    void skipsWhenAnotherReplicaOwnsTheTransactionLock() {
        var locks = mock(PostgresAdvisoryLocks.class);
        when(locks.tryLockTransaction("any2api-operation-reconciliation")).thenReturn(false);
        assertThat(new OperationReconciliationScheduler(mock(JdbcClient.class), locks).reconcile()).isZero();
    }

    private UUID event(JdbcClient jdbc, String domain, String aggregate, int attempt, String age) {
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO operation_events(id,correlation_id,domain,provider_id,operation,
                aggregate_type,aggregate_id,attempt,status,stage,started_at)
            VALUES(:id,:correlation,:domain,'mimo',:operation,'fixture',:aggregate,:attempt,
                'RUNNING','started',CURRENT_TIMESTAMP-CAST(:age AS interval))
            """).param("id", id).param("correlation", id.toString()).param("domain", domain)
            .param("operation", "REGISTRATION".equals(domain) ? "register" : "keepalive")
            .param("aggregate", aggregate).param("attempt", attempt).param("age", age).update();
        return id;
    }

    private String status(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT status FROM operation_events WHERE id=:id")
            .param("id", id).query(String.class).single();
    }
}
