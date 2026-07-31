package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.config.SecuritySettingsValidatorTest;
import org.junit.jupiter.api.Test;

class AdminSessionServiceTest {
    @Test
    void authenticatesSignedSessionAndRejectsCredentialsOrTokenTampering() {
        var properties = SecuritySettingsValidatorTest.configured();
        var service = new AdminSessionService(properties);

        var session = service.authenticate("admin", "admin-password-with-entropy").orElseThrow();

        assertThat(service.verify(session.token())).contains("admin");
        assertThat(service.authenticate("admin", "wrong-password")).isEmpty();
        assertThat(service.verify(session.token() + "tampered")).isEmpty();
    }
}
