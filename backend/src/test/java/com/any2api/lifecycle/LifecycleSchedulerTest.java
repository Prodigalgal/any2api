package com.any2api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import com.any2api.account.AccountStatus;
import org.junit.jupiter.api.Test;

class LifecycleSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

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
    void reauthenticatesWhenKeepalivePassesButInferenceRejectsTheCredential() {
        assertEquals("reauthenticate", LifecycleScheduler.nextAction(
            "keepalive", true, false, true));
        assertEquals("reauthenticate", LifecycleScheduler.nextAction(
            "reauthenticate", true, false, true));
    }

    @Test
    void probesRecoveredAndExplicitlyReauthenticatedAccounts() {
        assertEquals(true, LifecycleScheduler.requiresReadinessProbe(
            "reauthenticate", AccountStatus.ACTIVE, true));
        assertEquals(true, LifecycleScheduler.requiresReadinessProbe(
            "reauthenticate", AccountStatus.EXPIRED, true));
        assertEquals(false, LifecycleScheduler.requiresReadinessProbe(
            "keepalive", AccountStatus.ACTIVE, true));
    }
}
