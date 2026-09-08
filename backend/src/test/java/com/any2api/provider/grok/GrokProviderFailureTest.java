package com.any2api.provider.grok;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.proxy.ProxyPoolService;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.mock;

class GrokProviderFailureTest {

    @Test
    void permissionDeniedRemainsAmbiguousAndRetryable() {
        var mapper = new ObjectMapper();
        var provider = new GrokProvider(
            mock(OfficialBrowserTransportClient.class),
            new OfficialBrowserSemanticCommandFactory(mapper),
            new GrokProperties(),
            mock(ProxyPoolService.class),
            mapper);

        var failure = provider.classify(
            new GrokUpstreamException(403, "permission-denied"));

        assertThat(failure.type()).isEqualTo("permission_denied_unknown");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.detail()).containsEntry("attribution", "unknown");
        assertThat(failure.detail().get("candidates"))
            .isEqualTo(java.util.List.of("account", "email_domain", "egress_ip"));
    }
}
