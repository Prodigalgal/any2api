package com.any2api.provider.grok_web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class JsonObjectStreamFramer {
    private static final int MAX_FRAME_BYTES = 8 << 20;
    private final ObjectMapper mapper;
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
    private int depth;
    private boolean inString;
    private boolean escaped;

    JsonObjectStreamFramer(ObjectMapper mapper) { this.mapper = mapper; }

    List<JsonNode> decode(byte[] chunk) {
        var output = new ArrayList<JsonNode>();
        for (var value : chunk) {
            if (depth == 0) {
                if (value != '{') continue;
                frame.reset();
                depth = 1;
                inString = false;
                escaped = false;
                frame.write(value);
                continue;
            }
            frame.write(value);
            if (frame.size() > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("Grok Web response frame exceeded 8 MiB");
            }
            if (inString) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') inString = false;
                continue;
            }
            if (value == '"') inString = true;
            else if (value == '{') depth++;
            else if (value == '}' && --depth == 0) {
                output.add(mapper.readTree(frame.toString(StandardCharsets.UTF_8)));
            }
        }
        return output;
    }

    void finish() {
        if (depth != 0) throw new IllegalArgumentException("Grok Web response ended mid-frame");
    }
}
