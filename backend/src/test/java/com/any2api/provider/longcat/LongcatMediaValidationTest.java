package com.any2api.provider.longcat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LongcatMediaValidationTest {
    @Test
    void rejectsMalformedOrUnsupportedImagesBeforeAnAccountIsAcquired() {
        for (var source : List.of("data:image/png;base64,YQ==", "data:image/png;base64,%%%",
            "data:image/jpeg;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFMsAAAAASUVORK5CYII=",
            "data:image/webp;base64,YQ==")) {
            assertThatThrownBy(() -> LongcatMediaValidation.validate(request(source)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsPngAndJpegWithoutRewritingTheUploadedBytes() throws Exception {
        for (var format : List.of("png", "jpeg")) {
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32, 16,
                java.awt.image.BufferedImage.TYPE_INT_RGB), format, bytes);
            var source = "data:image/" + format + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
            LongcatMediaValidation.validate(request(source));
        }
    }

    private CanonicalRequest request(String source) {
        var mapper = new ObjectMapper();
        var user = mapper.createObjectNode().put("role", "user");
        user.putArray("content").addObject().put("type", "input_image").putObject("image_url").put("url", source);
        return new CanonicalRequest("media-validation", CanonicalRequest.Protocol.RESPONSES,
            "longcat", "longcat-flash", false, List.of(user), Map.of(), Map.of(), List.of(), Map.of(),
            mapper.createObjectNode());
    }
}
