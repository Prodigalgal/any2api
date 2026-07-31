package com.any2api.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseDataDecoderTest {
    @Test
    void preservesUtf8AndMultilineEventsAcrossTransportChunks() {
        var decoder = new SseDataDecoder();
        var bytes = "data: 你好\ndata: second\n\ndata: [DONE]\n\n"
            .getBytes(StandardCharsets.UTF_8);

        assertThat(decoder.decode(java.util.Arrays.copyOfRange(bytes, 0, 9))).isEmpty();
        assertThat(decoder.decode(java.util.Arrays.copyOfRange(bytes, 9, bytes.length)))
            .containsExactly("你好\nsecond", "[DONE]");
        assertThat(decoder.finish()).isEmpty();
    }
}
