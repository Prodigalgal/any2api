package com.any2api.provider.grok;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

class GrokProviderFailureTest {

    @Test
    void permissionDeniedRemainsAmbiguousAndRetryable() {
        var mapper = new ObjectMapper();
        var provider = new GrokProvider(
            WebClient.builder(), new GrokProperties(), new GrokRequestMapper(mapper), mapper);

        var failure = provider.classify(
            new GrokUpstreamException(403, "permission-denied"));

        assertThat(failure.type()).isEqualTo("permission_denied_unknown");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.detail()).containsEntry("attribution", "unknown");
        assertThat(failure.detail().get("candidates"))
            .isEqualTo(java.util.List.of("account", "email_domain", "egress_ip"));
    }
}
