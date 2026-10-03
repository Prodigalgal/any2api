package com.any2api.provider.longcat;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void acceptsUtf8DocumentsAndRejectsMalformedTextBeforeAcquiringAnAccount() {
        var source = "data:text/plain;base64," + java.util.Base64.getEncoder()
            .encodeToString("真实附件正文\nReference token: maple_913872".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var valid = fileRequest("fixture.txt", source);
        var original = valid.messages().getFirst().deepCopy();
        LongcatMediaValidation.validate(valid);
        assertThat(valid.messages().getFirst()).isEqualTo(original);
        assertThatThrownBy(() -> LongcatMediaValidation.validate(
            fileRequest("fixture.TXT", "data:application/octet-stream;base64,/w==")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8");
    }

    private CanonicalRequest fileRequest(String filename, String source) {
        var mapper = new ObjectMapper();
        var user = mapper.createObjectNode().put("role", "user");
        user.putArray("content").addObject().put("type", "file").putObject("file")
            .put("filename", filename).put("file_data", source);
        return new CanonicalRequest("text-validation", CanonicalRequest.Protocol.RESPONSES,
            "longcat", "longcat-flash", false, List.of(user), Map.of(), Map.of(), List.of(), Map.of(),
            mapper.createObjectNode());
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
