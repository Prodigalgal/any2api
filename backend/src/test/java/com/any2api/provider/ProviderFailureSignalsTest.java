package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderFailureSignalsTest {
    @Test
    void recognizesProviderChallengeMarkersAcrossClientErrors() {
        assertThat(ProviderFailureSignals.isAntiBot(400, "captcha validation failed")).isTrue();
        assertThat(ProviderFailureSignals.isAntiBot(403, "FAIL_SYS_USER_VALIDATE")).isTrue();
        assertThat(ProviderFailureSignals.isAntiBot(403, "H5Guard risk_control")).isTrue();
        assertThat(ProviderFailureSignals.isAntiBot(403, "x-amzn-waf-action: challenge")).isTrue();
        assertThat(ProviderFailureSignals.isAntiBot(502, "code=anti_bot_rejected")).isTrue();
    }

    @Test
    void doesNotTurnPlainForbiddenOrGenericServerTextIntoAntiBot() {
        assertThat(ProviderFailureSignals.isAntiBot(403, "forbidden")).isFalse();
        assertThat(ProviderFailureSignals.isAntiBot(502, "upstream service unavailable"))
            .isFalse();
        assertThat(ProviderFailureSignals.isAntiBot(502, "captcha configuration unavailable"))
            .isFalse();
    }
}
