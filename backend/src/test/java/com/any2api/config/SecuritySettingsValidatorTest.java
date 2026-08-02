package com.any2api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

public class SecuritySettingsValidatorTest {
    @Test
    void rejectsMissingDeploymentSecrets() {
        var properties = new Any2ApiProperties();

        assertThatThrownBy(() -> new SecuritySettingsValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ANY2API_ADMIN_PASSWORD");
    }

    @Test
    void acceptsExplicitStrongConfiguration() {
        var properties = configured();

        assertThatCode(() -> new SecuritySettingsValidator(properties).validate())
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsEightCharacterAdminPassword() {
        var properties = configured();
        properties.getSecurity().setAdminPassword("A1b2#xY8");

        assertThatCode(() -> new SecuritySettingsValidator(properties).validate())
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsSevenCharacterAdminPassword() {
        var properties = configured();
        properties.getSecurity().setAdminPassword("A1#b2X7");

        assertThatThrownBy(() -> new SecuritySettingsValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 8 characters");
    }

    public static Any2ApiProperties configured() {
        var properties = new Any2ApiProperties();
        var security = properties.getSecurity();
        security.setAdminPassword("admin-password-with-entropy");
        security.setPublicApiKey("public-api-key-with-enough-entropy");
        security.setInternalToken("internal-token-with-enough-entropy");
        security.setCredentialMasterKey(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]));
        return properties;
    }
}
