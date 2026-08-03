package com.any2api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperationEventServiceTest {

    @Test
    void sanitizesSecretsAndPrivateInputsBeforePersistence() {
        var sanitized = OperationEventService.sanitize("""
            failed {"token":"top secret", "password": "private-value"}
            for operator@example.test at https://example.test/callback?code=private
            image=data:image/png;base64,QUJDRA==
            """);

        assertThat(sanitized)
            .contains("<redacted>", "<email>", "<url-with-query>", "<embedded-data>")
            .doesNotContain(
                "top secret", "private-value", "operator@example.test", "code=private",
                "QUJDRA==");
    }
}
