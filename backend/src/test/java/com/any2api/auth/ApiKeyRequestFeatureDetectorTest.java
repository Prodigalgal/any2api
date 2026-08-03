package com.any2api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApiKeyRequestFeatureDetectorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiKeyRequestFeatureDetector detector = new ApiKeyRequestFeatureDetector();

    @Test
    void detectsToolsMultimodalAndInlineUploadsIndependently() {
        var request = mapper.createObjectNode();
        request.putArray("tools").addObject().put("type", "function");
        var content = request.putArray("messages").addObject()
            .put("role", "user").putArray("content");
        content.addObject().put("type", "image_url")
            .putObject("image_url").put("url", "data:image/png;base64,AA==");

        assertThat(detector.requiredFeatures(request)).isEqualTo(Set.of(
            ApiKeyFeature.TOOL_CALLING,
            ApiKeyFeature.MULTIMODAL_INPUT,
            ApiKeyFeature.FILE_UPLOADS));
    }

    @Test
    void remoteImageDoesNotRequireFileUploadPermission() {
        var request = mapper.createObjectNode();
        request.putArray("input").addObject().put("type", "input_image")
            .put("image_url", "https://example.test/image.png");

        assertThat(detector.requiredFeatures(request))
            .containsExactly(ApiKeyFeature.MULTIMODAL_INPUT);
    }
}
