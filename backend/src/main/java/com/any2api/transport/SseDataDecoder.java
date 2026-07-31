package com.any2api.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SseDataDecoder {
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final List<String> eventData = new ArrayList<>();

    public List<String> decode(byte[] chunk) {
        var output = new ArrayList<String>();
        if (chunk == null) return output;
        for (var value : chunk) {
            if (value == '\n') {
                processLine(output);
            } else {
                line.write(value);
            }
        }
        return output;
    }

    public List<String> finish() {
        var output = new ArrayList<String>();
        if (line.size() > 0) processLine(output);
        flush(output);
        return output;
    }

    private void processLine(List<String> output) {
        var value = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) {
            flush(output);
            return;
        }
        if (!value.startsWith("data:")) return;
        var data = value.substring(5);
        eventData.add(data.startsWith(" ") ? data.substring(1) : data);
    }

    private void flush(List<String> output) {
        if (eventData.isEmpty()) return;
        output.add(String.join("\n", eventData));
        eventData.clear();
    }
}
