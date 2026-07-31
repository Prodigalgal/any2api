package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.config.Any2ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

class AutomationProviderCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AutomationProviderCatalog catalog = new AutomationProviderCatalog(
        WebClient.builder(), new Any2ApiProperties());

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
    void rejectsUnknownOperationsWithoutReplacingLastGoodSnapshot() {
        catalog.replaceFrom(mapper.readTree(
            "{\"providers\":[{\"id\":\"alpha\",\"operations\":[\"register\"]}]}"));

        assertThatThrownBy(() -> catalog.replaceFrom(mapper.readTree(
            "{\"providers\":[{\"id\":\"alpha\",\"operations\":[\"unknown\"]}]}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported automation operation");
        assertThat(catalog.operationsFor("alpha")).containsExactly(AutomationOperation.REGISTER);
    }
}
