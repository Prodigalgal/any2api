package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderTransportFallbackPolicyTest {

    @Test
    void allowsOnlyChannelFailuresToFallBackToRuntime() {
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "provider_transport_error")).isTrue();
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "provider_upstream_error")).isTrue();
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "anti_bot_rejected")).isTrue();
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "rate_limited")).isTrue();

        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "credential_rejected")).isFalse();
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "invalid_request_error")).isFalse();
        assertThat(ProviderTransportFallbackPolicy.allowsRuntimeFallback(
            "quota_exhausted")).isFalse();
    }
}
