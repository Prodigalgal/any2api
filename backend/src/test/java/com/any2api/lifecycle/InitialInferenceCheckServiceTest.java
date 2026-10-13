package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.interop.InteropDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class InitialInferenceCheckServiceTest {
    @Test
    void repositoryBeanSupportsSpringExceptionTranslation() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class,
                () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class));
            context.registerBean(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class);
            context.registerBean(InitialInferenceCheckRepository.class);
            context.refresh();
            assertThat(context.getBean(InitialInferenceCheckRepository.class)).isNotNull();
        }
    }
    @Test
    void databaseClaimSurvivesConcurrencyFailuresCancellationAndServiceRestart() throws Exception {
        try (var database = new InteropDatabase(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var repository = new InitialInferenceCheckRepository(database.jdbc);
            var firstService = new InitialInferenceCheckService(repository, executor);
            var secondService = new InitialInferenceCheckService(new InitialInferenceCheckRepository(database.jdbc), executor);
            var account = account(database);
            var generations = new AtomicInteger();
            var checks = Flux.range(0, 8).flatMap(index -> (index % 2 == 0 ? firstService : secondService)
                .verify(account, () -> new InitialInferenceCheckService.Plan("basic", () -> {
                    generations.incrementAndGet();
                    return Mono.just(InferenceReadinessProbe.Result.ready("basic"))
                        .delayElement(Duration.ofMillis(100));
                })), 8).collectList().block(Duration.ofSeconds(10));
            assertThat(generations).hasValue(1);
            assertThat(checks).filteredOn(InitialInferenceCheckService.Observation::performed).hasSize(1);
            var cached = secondService.verify(account, () -> { throw new AssertionError("cached plans must not run"); })
                .block(Duration.ofSeconds(2));
            assertThat(cached.performed()).isFalse();
            assertThat(cached.result().ready()).isTrue();

            var failed = account(database);
            var failure = firstService.verify(failed, () -> new InitialInferenceCheckService.Plan("basic",
                () -> Mono.error(new IllegalStateException("upstream failed")))).block(Duration.ofSeconds(2));
            assertThat(failure.result().ready()).isFalse();
            assertThat(secondService.verify(failed, () -> { throw new AssertionError("no retry after failure"); })
                .block(Duration.ofSeconds(2)).performed()).isFalse();

            var empty = account(database);
            assertThat(firstService.verify(empty, () -> new InitialInferenceCheckService.Plan("basic", Mono::empty))
                .block(Duration.ofSeconds(2)).result().errorClass()).isEqualTo("InitialVerificationEmptyResult");
            assertThat(repository.find(empty).orElseThrow().status()).isEqualTo("FAILED");

            var cancelled = account(database);
            var started = new CountDownLatch(1);
            var subscription = firstService.verify(cancelled, () -> new InitialInferenceCheckService.Plan("basic",
                () -> Mono.<InferenceReadinessProbe.Result>never().doOnSubscribe(ignored -> started.countDown())))
                .subscribe();
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                subscription.dispose();
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(repository.find(cancelled).orElseThrow().errorClass()).isEqualTo("InitialVerificationInterrupted"));
            assertThat(secondService.verify(cancelled, () -> { throw new AssertionError("no retry after cancellation"); })
                .block(Duration.ofSeconds(2)).performed()).isFalse();

            var crashed = account(database);
            repository.claim(crashed, "basic");
            var interrupted = secondService.verify(crashed, () -> { throw new AssertionError("no retry after process exit"); })
                .block(Duration.ofSeconds(2));
            assertThat(interrupted.performed()).isFalse();
            assertThat(interrupted.result().errorClass()).isEqualTo("InitialVerificationInProgress");

            var advanced = account(database);
            assertThatThrownBy(() -> firstService.verify(advanced, () ->
                new InitialInferenceCheckService.Plan("claude-sonnet", () -> { throw new AssertionError("no paid call"); }))
                .block(Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.find(advanced)).isEmpty();
            assertThat(generations).hasValue(1);
        }
    }

    @Test
    void historicalChecksAreImportedWithoutReadingUsageOrChangingAccounts() throws Exception {
        try (var database = new InteropDatabase(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = account(database);
            var failed = account(database);
            database.jdbc.sql("UPDATE accounts SET status='EXPIRED',enabled=FALSE,metadata="
                    + "'{\"inference_probe_at\":\"2026-10-12T00:00:00Z\",\"inference_probe_model\":\"basic\",\"inference_probe_status\":\"READY\"}'::jsonb WHERE id=:id")
                .param("id", ready).update();
            database.jdbc.sql("UPDATE accounts SET metadata="
                    + "'{\"inference_probe_at\":\"2026-10-12T00:00:00Z\",\"inference_probe_status\":\"FAILED\",\"inference_probe_error\":\"rate_limited\"}'::jsonb WHERE id=:id")
                .param("id", failed).update();
            String sql;
            try (var stream = getClass().getResourceAsStream("/db/changelog/releases/034-initial-inference-check.sql")) {
                sql = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var insert = sql.substring(sql.indexOf("INSERT INTO"), sql.indexOf(";", sql.indexOf("INSERT INTO")));
            assertThat(insert).doesNotContain("usage_events");
            assertThat(database.jdbc.sql(insert).update()).isEqualTo(2);
            assertThat(database.jdbc.sql(insert).update()).isZero();
            var repository = new InitialInferenceCheckRepository(database.jdbc);
            var service = new InitialInferenceCheckService(repository, executor);
            for (var id : java.util.List.of(ready, failed)) {
                var check = service.verify(id, () -> { throw new AssertionError("historical accounts must not generate"); })
                    .block(Duration.ofSeconds(2));
                assertThat(check.performed()).isFalse();
                assertThat(check.source()).isEqualTo("HISTORICAL");
            }
            assertThat(repository.find(ready).orElseThrow().result().ready()).isTrue();
            assertThat(repository.find(failed).orElseThrow().errorClass()).isEqualTo("rate_limited");
            assertThat(database.jdbc.sql("SELECT status FROM accounts WHERE id=:id").param("id", ready)
                .query(String.class).single()).isEqualTo("EXPIRED");
            assertThat(database.jdbc.sql("SELECT enabled FROM accounts WHERE id=:id").param("id", ready)
                .query(Boolean.class).single()).isFalse();
        }
    }

    @Test
    void cachedFailuresDoNotPenalizeAnActiveAccountAndCachedSuccessCannotUndoManualDisablement() {
        var failed = new InitialInferenceCheckService.Observation(
            InferenceReadinessProbe.Result.failed("basic", "rate_limited"), false, "HISTORICAL", Instant.now());
        var active = LifecycleScheduler.Verification.from(failed, "keepalive", com.any2api.account.AccountStatus.ACTIVE);
        assertThat(active.probe().ready()).isTrue();
        assertThat(active.probe().model()).isBlank();
        assertThat(active.activationVerified()).isFalse();
        var ready = new InitialInferenceCheckService.Observation(
            InferenceReadinessProbe.Result.ready("basic"), false, "HISTORICAL", Instant.now());
        assertThat(LifecycleScheduler.Verification.from(ready, "keepalive", com.any2api.account.AccountStatus.DISABLED)
            .activationVerified()).isFalse();
        assertThat(LifecycleScheduler.Verification.from(ready, "keepalive", com.any2api.account.AccountStatus.EXPIRED)
            .freshReady()).isFalse();
    }

    private UUID account(InteropDatabase database) {
        var id = UUID.randomUUID();
        database.jdbc.sql("INSERT INTO accounts(id,provider_id,external_id,status,enabled) VALUES(:id,'mimo',:external,'PENDING',FALSE)")
            .param("id", id).param("external", id.toString()).update();
        return id;
    }
}
