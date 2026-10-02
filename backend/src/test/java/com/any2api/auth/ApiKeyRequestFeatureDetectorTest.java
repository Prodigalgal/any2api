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

    @Test
    void toolHistoryRequiresPermissionWithoutNewToolDefinitions() {
        for (var type : Set.of("function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output")) {
            var request = mapper.createObjectNode();
            request.putArray("input").addObject().put("type", type).put("call_id", "one");
            assertThat(detector.requiredFeatures(request)).containsExactly(ApiKeyFeature.TOOL_CALLING);
        }
        var chat = mapper.createObjectNode();
        chat.putArray("messages").addObject().put("role", "tool").put("tool_call_id", "one").put("content", "result");
        assertThat(detector.requiredFeatures(chat)).containsExactly(ApiKeyFeature.TOOL_CALLING);
    }
}
