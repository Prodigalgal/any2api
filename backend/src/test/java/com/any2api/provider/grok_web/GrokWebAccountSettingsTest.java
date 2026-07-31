package com.any2api.provider.grok_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.BrowserClearanceCoordinator;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class GrokWebAccountSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void accountInitializationUsesOneMultiOriginSessionAndCapturedProtocols() {
        var transport = mock(BrowserTransportClient.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var properties = new GrokWebProperties();
        var opens = new ArrayList<BrowserTransportClient.OpenCommand>();
        var requests = new ArrayList<BrowserTransportClient.Request>();
        when(transport.open(any())).thenAnswer(invocation -> {
            opens.add(invocation.getArgument(0));
            return Mono.just(new BrowserTransportClient.Session("session", "ua", "chrome136"));
        });
        when(transport.request(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            requests.add(request);
            var body = request.path().equals("/index")
                ? "<html>index</html>".getBytes(StandardCharsets.UTF_8) : new byte[0];
            return Mono.just(new BrowserTransportClient.BufferedResponse(200, "ok", body));
        });
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(mapper.createObjectNode())));
        when(signer.sign(anyString(), anyString(), anyString())).thenReturn("signed");
        var protocol = new GrokWebProtocolClient(transport,
            mock(BrowserClearanceCoordinator.class), signer, properties, mapper);

        var result = protocol.applyAccountSettings(
            mapper.createObjectNode().put("sso", "secret"), Map.of(),
            new GrokWebProtocolClient.AccountSettings(
                true, LocalDate.of(2000, 1, 2), true), "identity-group").block();

        assertThat(result).isNotNull();
        assertThat(result.metadataPatch().path("web_terms_version").asInt()).isEqualTo(5);
        assertThat(result.metadataPatch().has("web_birth_date_set_at")).isTrue();
        assertThat(result.metadataPatch().has("web_nsfw_enabled_at")).isTrue();
        assertThat(opens).singleElement().satisfies(command -> {
            assertThat(command.origin()).isEqualTo(URI.create("https://grok.com"));
            assertThat(command.origins()).containsExactly(URI.create("https://accounts.x.ai"));
            assertThat(command.cookieDomains()).containsExactly(".grok.com", ".x.ai");
            assertThat(command.proxyAffinityKey()).isEqualTo("identity-group");
            assertThat(command.strictProxyAffinity()).isTrue();
        });
        assertThat(requests.stream().filter(request -> request.path().equals("/index")))
            .hasSize(1);
        var accountTerms = requestEndingWith(requests, "SetTosAcceptedVersion");
        assertThat(accountTerms.origin()).isEqualTo(URI.create("https://accounts.x.ai"));
        assertThat(accountTerms.refererPath()).isEqualTo("/accept-tos");
        assertThat(accountTerms.rawBody()).containsExactly(0, 0, 0, 0, 2, 0x10, 1);
        var productTerms = request(requests, "/rest/auth/set-tos-accepted");
        assertThat(productTerms.body().path("tosVersion").asInt()).isEqualTo(5);
        assertThat(productTerms.headers()).containsEntry("x-statsig-id", "signed");
        var birth = request(requests, "/rest/auth/set-birth-date");
        assertThat(birth.body().path("birthDate").asText())
            .isEqualTo("2000-01-02T16:00:00.000Z");
        var nsfw = requestEndingWith(requests, "UpdateUserFeatureControls");
        assertThat(nsfw.rawBody()).isNotEmpty();
        assertThat(new String(nsfw.rawBody(), StandardCharsets.ISO_8859_1))
            .contains("always_show_nsfw_content");
    }

    @Test
    void grpcWebTrailerFailureRejectsHttpSuccess() {
        var transport = mock(BrowserTransportClient.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var properties = new GrokWebProperties();
        when(transport.open(any())).thenReturn(Mono.just(
            new BrowserTransportClient.Session("session", "ua", "chrome136")));
        when(transport.request(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            var body = request.path().endsWith("UpdateUserFeatureControls")
                ? trailer("7") : "<html>index</html>".getBytes(StandardCharsets.UTF_8);
            return Mono.just(new BrowserTransportClient.BufferedResponse(200, "ok", body));
        });
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(mapper.createObjectNode())));
        when(signer.sign(anyString(), anyString(), anyString())).thenReturn("signed");
        var protocol = new GrokWebProtocolClient(transport,
            mock(BrowserClearanceCoordinator.class), signer, properties, mapper);

        assertThatThrownBy(() -> protocol.applyAccountSettings(
                mapper.createObjectNode().put("sso", "secret"), Map.of(),
                new GrokWebProtocolClient.AccountSettings(false, null, true), "").block())
            .hasMessage("Grok Web account setting gRPC status 7");
    }

    private BrowserTransportClient.Request request(
        List<BrowserTransportClient.Request> requests,
        String path
    ) {
        return requests.stream().filter(value -> value.path().equals(path)).findFirst()
            .orElseThrow();
    }

    private BrowserTransportClient.Request requestEndingWith(
        List<BrowserTransportClient.Request> requests,
        String suffix
    ) {
        return requests.stream().filter(value -> value.path().endsWith(suffix)).findFirst()
            .orElseThrow();
    }

    private byte[] trailer(String status) {
        var payload = ("grpc-status: " + status + "\r\n").getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(5 + payload.length).put((byte) 0x80)
            .putInt(payload.length).put(payload).array();
    }
}
