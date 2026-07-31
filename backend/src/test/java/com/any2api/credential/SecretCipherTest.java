package com.any2api.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
    @Test
    void bindsCiphertextToProxyPoolRevision() {
        var properties = new Any2ApiProperties();
        properties.getSecurity().setCredentialMasterKey(
            Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new SecretCipher(properties);
        var plaintext = "sensitive proxy source".getBytes(StandardCharsets.UTF_8);

        var sealed = cipher.seal(plaintext, "proxy-pool:id:1");

        assertThat(cipher.open(sealed.encrypted(), sealed.nonce(), "proxy-pool:id:1"))
            .isEqualTo(plaintext);
        assertThatThrownBy(() -> cipher.open(
            sealed.encrypted(), sealed.nonce(), "proxy-pool:id:2"))
            .isInstanceOf(IllegalStateException.class);
    }
}
