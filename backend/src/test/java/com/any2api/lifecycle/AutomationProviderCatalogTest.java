package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.ProviderAction;
import com.any2api.provider.ProviderInstallationCatalog;
import com.any2api.provider.ProviderTransportMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class AutomationProviderCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AutomationProviderCatalog catalog = new AutomationProviderCatalog(
        WebClient.builder(), new Any2ApiProperties(), mock(ProviderInstallationCatalog.class));

    @Test
    void refreshSkipsDisabledDedicatedTargetsAndDeduplicatesSharedUrls() {
        var properties = new Any2ApiProperties();
        var shared = URI.create("http://automation.example");
        var arena = URI.create("http://arena.example");
        properties.getAutomation().setBaseUrl(shared);
        properties.getAutomation().setProviderUrls(Map.of(
            "grok_web", shared, "arena", arena, "qwen", URI.create("http://qwen.example")));
        var installations = mock(ProviderInstallationCatalog.class);
        when(installations.isEnabled("grok_web")).thenReturn(true);
        when(installations.isEnabled("arena")).thenReturn(true);
        var requests = new ArrayList<URI>();
        var discovery = discoveryCatalog(properties, installations, requests);

        discovery.refresh();

        assertThat(requests).containsExactly(
            shared.resolve("/internal/v1/capabilities"),
            arena.resolve("/internal/v1/capabilities"));
        assertThat(discovery.ready()).isTrue();
    }

    @Test
    void refreshResumesDedicatedDiscoveryWhenProviderIsEnabled() {
        var properties = new Any2ApiProperties();
        var shared = URI.create("http://automation.example");
        var dedicated = URI.create("http://dedicated.example");
        properties.getAutomation().setBaseUrl(shared);
        properties.getAutomation().setProviderUrls(Map.of("alpha", dedicated));
        var installations = mock(ProviderInstallationCatalog.class);
        var requests = new ArrayList<URI>();
        var discovery = discoveryCatalog(properties, installations, requests);

        discovery.refresh();
        assertThat(requests).containsExactly(shared.resolve("/internal/v1/capabilities"));

        requests.clear();
        when(installations.isEnabled("alpha")).thenReturn(true);
        discovery.refresh();
        assertThat(requests).containsExactly(
            shared.resolve("/internal/v1/capabilities"),
            dedicated.resolve("/internal/v1/capabilities"));

        requests.clear();
        when(installations.isEnabled("alpha")).thenReturn(false);
        discovery.refresh();
        assertThat(requests).containsExactly(shared.resolve("/internal/v1/capabilities"));
    }

    private static AutomationProviderCatalog discoveryCatalog(
        Any2ApiProperties properties,
        ProviderInstallationCatalog installations,
        List<URI> requests
    ) {
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {"providers":[{"id":"%s","operations":["keepalive"]}]}
                    """.formatted(request.url().getHost().split("\\.")[0]))
                .build());
        });
        return new AutomationProviderCatalog(builder, properties, installations);
    }

    @Test
    void replacesCatalogAtomicallyFromAutomationManifests() {
        catalog.replaceFrom(mapper.readTree("""
            {"providers":[
              {"id":"alpha","operations":["keepalive","register"],
               "registration_attempt_mode":"single_identity"},
              {"id":"beta","operations":["reauthenticate"]}
            ]}
            """));

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.operationsFor("alpha"))
            .containsExactlyInAnyOrder(AutomationOperation.REGISTER, AutomationOperation.KEEPALIVE);
        assertThat(catalog.operationsFor("missing")).isEmpty();
        assertThat(catalog.registrationAttemptMode("alpha"))
            .isEqualTo(RegistrationAttemptMode.SINGLE_IDENTITY);
        assertThat(catalog.registrationAttemptMode("beta"))
            .isEqualTo(RegistrationAttemptMode.NEW_IDENTITY);
    }

    @Test
    void mergesManifestsFromMultipleAutomationTargets() {
        catalog.replaceFrom(List.of(
            mapper.readTree("""
                {"providers":[
                  {"id":"grok_web","operations":["keepalive","register"]}
                ]}
                """),
            mapper.readTree("""
                {"providers":[
                  {"id":"arena","operations":["reauthenticate","register"]}
                ]}
                """)
        ));

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.operationsFor("grok_web"))
            .containsExactlyInAnyOrder(AutomationOperation.REGISTER, AutomationOperation.KEEPALIVE);
        assertThat(catalog.operationsFor("arena"))
            .containsExactlyInAnyOrder(AutomationOperation.REGISTER, AutomationOperation.REAUTHENTICATE);
    }

    @Test
    void rejectsUnknownOperationsWithoutReplacingLastGoodSnapshot() {
        catalog.replaceFrom(mapper.readTree(
            "{\"providers\":[{\"id\":\"alpha\",\"operations\":[\"register\"]}]}"));

        assertThatThrownBy(() -> catalog.replaceFrom(mapper.readTree(
            "{\"providers\":[{\"id\":\"alpha\",\"operations\":[\"unknown\"]}]}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported automation operation");
        assertThat(catalog.operationsFor("alpha")).containsExactly(AutomationOperation.REGISTER);
    }

    @Test
    void preservesProviderRegistrationSafetyLimitsFromAutomationManifest() {
        catalog.replaceFrom(mapper.readTree("""
            {"providers":[{"id":"arena","operations":["register"],
              "registration_attempt_mode":"single_identity",
              "registration_max_target":1,"registration_max_attempts":1}]}
            """));

        assertThat(catalog.registrationAttemptMode("arena"))
            .isEqualTo(RegistrationAttemptMode.SINGLE_IDENTITY);
        assertThat(catalog.registrationMaxTarget("arena")).isEqualTo(1);
        assertThat(catalog.registrationMaxAttempts("arena")).isEqualTo(1);
    }

    @Test
    void indexesActionBindingsAndRejectsAutoAsAConcreteChannel() {
        catalog.replaceFrom(mapper.readTree("""
            {"providers":[{"id":"minmax","operations":["keepalive"],
              "actions":[
                {"action":"chat","channel":"api","stream":true,"request":true},
                {"action":"chat","channel":"camoufox_browser_runtime","stream":true,"request":false}
              ]}]}
            """));

        assertThat(catalog.supportsAction(
            "minmax", ProviderAction.CHAT, ProviderTransportMode.API)).isTrue();
        assertThat(catalog.actionsFor("minmax"))
            .contains(new AutomationProviderCatalog.ProviderActionBinding(
                ProviderAction.CHAT, ProviderTransportMode.API, true, true));

        assertThatThrownBy(() -> catalog.replaceFrom(mapper.readTree("""
            {"providers":[{"id":"minmax","operations":["keepalive"],
              "actions":[{"action":"chat","channel":"auto","stream":true,"request":true}]}]}
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be AUTO");
        assertThat(catalog.supportsAction(
            "minmax", ProviderAction.CHAT, ProviderTransportMode.API)).isTrue();
    }
}
