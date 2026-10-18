package com.any2api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import com.any2api.account.AccountStatus;
import org.junit.jupiter.api.Test;

class LifecycleSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void defersRedundantKeepaliveFromTheLastRealInferenceButRespectsCredentialRefresh() {
        assertEquals(java.util.Optional.of(NOW.plusSeconds(1200)),
            LifecycleScheduler.recentInferenceKeepaliveDueAt(NOW.minusSeconds(600), null,
                NOW, Duration.ofMinutes(30)));
        assertEquals(java.util.Optional.empty(), LifecycleScheduler.recentInferenceKeepaliveDueAt(
            NOW.minusSeconds(1800), null, NOW, Duration.ofMinutes(30)));
        assertEquals(java.util.Optional.empty(), LifecycleScheduler.recentInferenceKeepaliveDueAt(
            NOW.plusSeconds(1), null, NOW, Duration.ofMinutes(30)));
        assertEquals(java.util.Optional.empty(), LifecycleScheduler.recentInferenceKeepaliveDueAt(
            NOW.minusSeconds(60), NOW.plusSeconds(600), NOW, Duration.ofMinutes(30)));
        assertEquals(java.util.Optional.of(NOW.plusSeconds(1800)),
            LifecycleScheduler.recentInferenceKeepaliveDueAt(NOW.minusSeconds(60),
                NOW.plusSeconds(3000), NOW, Duration.ofHours(6)));
    }

    @Test
    void schedulesCredentialRefreshBeforeProviderExpiry() {
        assertEquals(
            Duration.ofHours(1).minusMinutes(20),
            LifecycleScheduler.healthyInterval(NOW.plus(Duration.ofHours(1)), NOW));
    }

    @Test
    void clampsNearExpiryCredentialsToAvoidImmediateRetryStorm() {
        assertEquals(
            Duration.ofMinutes(5),
            LifecycleScheduler.healthyInterval(NOW.plus(Duration.ofMinutes(10)), NOW));
    }

    @Test
    void keepsTheNormalCeilingForLongLivedCredentials() {
        assertEquals(
            Duration.ofHours(6),
            LifecycleScheduler.healthyInterval(NOW.plus(Duration.ofDays(1)), NOW));
    }

    @Test
    void appliesTheProviderSpecificHealthyIntervalCeiling() {
        assertEquals(
            Duration.ofMinutes(90),
            LifecycleScheduler.healthyInterval(
                NOW.plus(Duration.ofDays(1)), NOW, Duration.ofMinutes(90)));
    }

    @Test
    void identifiesExpiredCredentialWindows() {
        assertEquals(true, LifecycleScheduler.isCredentialExpired(
            NOW.minusSeconds(1), NOW));
        assertEquals(false, LifecycleScheduler.isCredentialExpired(
            NOW.plusSeconds(1), NOW));
        assertEquals(false, LifecycleScheduler.isCredentialExpired(null, NOW));
    }

    @Test
    void clearsAnExpiredCredentialFenceAfterSuccessfulReauthenticationWithoutProviderExpiry() {
        assertEquals(true, LifecycleScheduler.shouldClearStaleCredentialExpiry(
            "reauthenticate", null, NOW.minus(Duration.ofMinutes(1)), NOW));
        assertEquals(false, LifecycleScheduler.shouldClearStaleCredentialExpiry(
            "keepalive", null, NOW.minus(Duration.ofMinutes(1)), NOW));
        assertEquals(false, LifecycleScheduler.shouldClearStaleCredentialExpiry(
            "reauthenticate", NOW.plus(Duration.ofDays(1)),
            NOW.minus(Duration.ofMinutes(1)), NOW));
    }

    @Test
    void clearsAnExpiredCredentialFenceWhenInferenceProbeIsReady() {
        assertEquals(true, LifecycleScheduler.shouldClearStaleCredentialExpiry(
            "keepalive", null, NOW.minus(Duration.ofMinutes(1)), NOW, true));
        assertEquals(false, LifecycleScheduler.shouldClearStaleCredentialExpiry(
            "keepalive", null, NOW.minus(Duration.ofMinutes(1)), NOW, false));
    }

    @Test
    void reauthenticatesWhenKeepalivePassesButInferenceRejectsTheCredential() {
        assertEquals("reauthenticate", LifecycleScheduler.nextAction(
            "keepalive", true, false, true));
        assertEquals("reauthenticate", LifecycleScheduler.nextAction(
            "reauthenticate", true, false, true));
    }

    @Test
    void fallsBackToKeepaliveOrCheckinWhenReauthenticationIsNotSupported() {
        assertEquals("keepalive", LifecycleScheduler.nextAction(
            "keepalive", false, true, false,
            AccountStatus.ACTIVE, true, false, false));
        assertEquals("daily_checkin", LifecycleScheduler.nextAction(
            "keepalive", false, true, false,
            AccountStatus.ACTIVE, true, true, false));
    }

    @Test
    void checksTheOnceOnlyBudgetForAllHealthyImportedAccounts() {
        assertEquals(true, LifecycleScheduler.requiresInitialVerification(
            "reauthenticate", AccountStatus.ACTIVE, true));
        assertEquals(true, LifecycleScheduler.requiresInitialVerification(
            "reauthenticate", AccountStatus.EXPIRED, true));
        assertEquals(true, LifecycleScheduler.requiresInitialVerification(
            "keepalive", AccountStatus.ACTIVE, true));
    }

    @Test
    void dailyCheckinRunsBeforeInferenceReadinessForMinmax() {
        assertEquals(false, LifecycleScheduler.requiresInitialVerification(
            "daily_checkin", AccountStatus.PENDING, true, true));
        assertEquals(true, LifecycleScheduler.requiresInitialVerification(
            "reauthenticate", AccountStatus.EXPIRED, true, true));
        assertEquals(true, LifecycleScheduler.requiresInitialVerification(
            "keepalive", AccountStatus.PENDING, true, true));
    }

    @Test
    void dailyCheckinTransitionsPendingAccountsToKeepaliveAndActiveAccountsBackToDaily() {
        assertEquals("keepalive", LifecycleScheduler.nextAction(
            "daily_checkin", true, false, false,
            AccountStatus.PENDING, false, true));
        assertEquals("daily_checkin", LifecycleScheduler.nextAction(
            "keepalive", true, false, false,
            AccountStatus.ACTIVE, true, true));
        assertEquals("daily_checkin", LifecycleScheduler.nextAction(
            "reauthenticate", true, false, false,
            AccountStatus.ACTIVE, true, true));
    }

    @Test
    void includesTheAccountProbeModelAndErrorInTheLifecycleFailureDetail() {
        var probe = InferenceReadinessProbe.Result.failed("longcat-pro", "TimeoutException");
        var result = LifecycleResult.healthy(null, null);

        assertEquals(
            "inference readiness probe failed model=longcat-pro error=TimeoutException",
            LifecycleScheduler.readinessFailureDetail(probe, result));
    }
}
