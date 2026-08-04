package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderRetryPolicyTest {
    @Test
    void standardPolicyRetriesOnlyFinitePreOutputAccountFailures() {
        var policy = ProviderRetryPolicy.standard(3);

        assertThat(policy.shouldRetry("empty_model_response", 1)).isTrue();
        assertThat(policy.shouldRetry("credential_rejected", 1)).isTrue();
        assertThat(policy.shouldRetry("account_blocked", 1)).isTrue();
        assertThat(policy.shouldRetry("rate_limited", 1)).isTrue();
        assertThat(policy.shouldRetry("quota_exhausted", 1)).isTrue();
        assertThat(policy.shouldRetry("provider_protocol_violation", 1)).isFalse();
        assertThat(policy.shouldRetry("rate_limited", 3)).isFalse();
    }
}
