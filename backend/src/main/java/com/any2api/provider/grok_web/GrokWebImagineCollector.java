package com.any2api.provider.grok_web;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GrokWebImagineCollector {
    private final ObjectMapper mapper;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private int terminalCount;

    GrokWebImagineCollector(ObjectMapper mapper) { this.mapper = mapper; }

    boolean accept(byte[] frame, int desiredCount, int terminalExpected) {
        var message = mapper.readTree(frame);
        var type = message.path("type").asText("");
        if ("error".equals(type)) {
            throw new GrokWebEventDecoder.GrokWebStreamException(
                "imagine_error",
                message.path("message").asText("Grok Imagine WebSocket returned an error"));
        }
        if (!"image".equals(type) && !"json".equals(type)) {
            return done(desiredCount, terminalExpected);
        }
        var rawUrl = message.path("url").asText("");
        var id = first(message, "image_id", "job_id", "id");
        if (id.isBlank() && !rawUrl.isBlank()) id = imageId(rawUrl);
        if (id.isBlank()) return done(desiredCount, terminalExpected);
        var finalId = id;
        var slot = slots.computeIfAbsent(finalId, ignored -> new Slot(finalId));
        if ("image".equals(type)) {
            if (message.has("side_by_side_index")) {
                slot.position = message.path("side_by_side_index").asInt();
                slot.positionKnown = true;
            }
            var progress = message.path("percentage_complete").asInt(100);
            if (progress >= 100) {
                slot.url = rawUrl;
                slot.blob = message.path("blob").asText("");
                slot.finalImage = !slot.url.isBlank() || !slot.blob.isBlank();
            }
            return done(desiredCount, terminalExpected);
        }
        if (message.has("order") && !slot.positionKnown) {
            slot.position = message.path("order").asInt();
            slot.positionKnown = true;
        }
        if (!"completed".equals(message.path("current_status").asText(""))) {
            return done(desiredCount, terminalExpected);
        }
        if (!rawUrl.isBlank() && !slot.finalImage) {
            slot.url = rawUrl;
            slot.blob = message.path("blob").asText("");
            slot.finalImage = true;
        }
        if (!slot.completed) {
            slot.completed = true;
            terminalCount++;
        }
        slot.moderated = message.path("moderated").asBoolean(false);
        return done(desiredCount, terminalExpected);
    }

    List<ImageValue> images(int count) {
        return slots.values().stream()
            .filter(slot -> slot.completed && !slot.moderated && slot.finalImage)
            .sorted(Comparator
                .comparing((Slot slot) -> !slot.positionKnown)
                .thenComparingInt(slot -> slot.position)
                .thenComparing(slot -> slot.id))
            .limit(count)
            .map(slot -> new ImageValue(slot.url, slot.blob))
            .toList();
    }

    private boolean done(int desiredCount, int terminalExpected) {
        return usableCount() >= desiredCount || terminalCount >= terminalExpected;
    }

    private int usableCount() {
        return (int) slots.values().stream()
            .filter(slot -> slot.completed && !slot.moderated && slot.finalImage)
            .count();
    }

    private String first(JsonNode value, String... fields) {
        for (var field : fields) {
            var candidate = value.path(field).asText("").trim();
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }

    private String imageId(String value) {
        try {
            var path = URI.create(value).getPath();
            var name = path.substring(path.lastIndexOf('/') + 1);
            var dot = name.indexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    record ImageValue(String url, String blob) {}

    private static final class Slot {
        private final String id;
        private String url = "";
        private String blob = "";
        private int position;
        private boolean positionKnown;
        private boolean finalImage;
        private boolean completed;
        private boolean moderated;

        private Slot(String id) { this.id = id; }
    }
}
