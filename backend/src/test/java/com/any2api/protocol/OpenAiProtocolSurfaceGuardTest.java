package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiProtocolSurfaceGuardTest {

    @Test
    void onlyOpenAiChatAndResponsesProtocolsAreExposed() throws IOException {
        assertThat(Set.of(CanonicalRequest.Protocol.values()))
            .containsExactlyInAnyOrder(
                CanonicalRequest.Protocol.CHAT_COMPLETIONS,
                CanonicalRequest.Protocol.RESPONSES);

        try (var sources = Files.walk(Path.of("src/main/java"))) {
            var forbidden = sources.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        var content = Files.readString(path);
                        return content.contains("/v1/messages")
                            || content.contains("input_schema")
                            || content.contains("tool_use");
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .toList();
            assertThat(forbidden)
                .as("Anthropic protocol markers are forbidden in the backend surface")
                .isEmpty();
        }
    }
}
