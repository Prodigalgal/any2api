package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProviderTransportBoundaryArchitectureTest {
    @Test
    void allInferenceProvidersDelegatePhysicalTransportToSharedActionClient() throws IOException {
        var providers = List.of(
            "deepseek/DeepseekProvider.java",
            "glm/GlmProvider.java",
            "grok/GrokProvider.java",
            "grok_console/GrokConsoleProvider.java",
            "grok_web/GrokWebProvider.java",
            "longcat/LongcatProvider.java",
            "mimo/MimoProvider.java",
            "minmax/MinmaxProvider.java",
            "qwen/QwenProvider.java");
        for (var relative : providers) {
            var source = Files.readString(Path.of("src/main/java/com/any2api/provider", relative));
            assertThat(source).as(relative)
                .contains("OfficialBrowserTransportClient")
                .contains("OfficialBrowserSemanticCommandFactory")
                .doesNotContain("org.springframework.web.reactive.function.client.WebClient")
                .doesNotContain("import com.any2api.transport.BrowserTransportClient")
                .doesNotContain("private final BrowserTransportClient");
        }
    }
}
