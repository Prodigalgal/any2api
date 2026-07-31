package com.any2api.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaInputValidationTest {
    @Test
    void detectsRasterContentInsteadOfTrustingMultipartHeaders() {
        var png = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0
        };

        assertThat(MediaInputValidation.requireRasterImage("image/png", png))
            .isEqualTo("image/png");
        assertThatThrownBy(() -> MediaInputValidation.requireRasterImage("image/jpeg", png))
            .hasMessageContaining("does not match");
        assertThatThrownBy(() -> MediaInputValidation.requireRasterImage(
            "image/png", "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .hasMessageContaining("PNG, JPEG, WebP, GIF, or AVIF");
    }
}
