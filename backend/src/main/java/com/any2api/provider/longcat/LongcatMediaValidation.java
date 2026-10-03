package com.any2api.provider.longcat;

import com.any2api.media.MediaInputValidation;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import tools.jackson.databind.JsonNode;

final class LongcatMediaValidation {
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image", "input_image", "image_url");
    private static final Set<String> FILE_TYPES = Set.of("file", "input_file", "attachment");
    private static final List<String> SOURCE_FIELDS = List.of(
        "image_url", "input_image", "image", "file", "input_file", "attachment", "source",
        "url", "file_url", "file_data", "data", "base64");

    private LongcatMediaValidation() {}

    static void validate(CanonicalRequest request) {
        for (var message : request.messages()) {
            if (!message.path("content").isArray()) continue;
            for (var part : message.path("content")) {
                var type = part.path("type").asText("");
                var image = IMAGE_TYPES.contains(type);
                if (!image && !FILE_TYPES.contains(type)) continue;
                var source = inlineSource(part);
                var separator = source.indexOf(";base64,");
                if (!source.startsWith("data:") || separator < 6) {
                    throw new IllegalArgumentException("LongCat media requires an inline base64 data URL");
                }
                var encoded = source.substring(separator + 8);
                if (encoded.length() > 4 * ((MAX_UPLOAD_BYTES + 2) / 3)) {
                    throw new IllegalArgumentException("LongCat media exceeds the 10 MiB upload limit");
                }
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("LongCat media contains invalid base64", error);
                }
                if (content.length == 0 || content.length > MAX_UPLOAD_BYTES) {
                    throw new IllegalArgumentException("LongCat media must contain between 1 byte and 10 MiB");
                }
                if (image) validateImage(source.substring(5, separator).toLowerCase(Locale.ROOT), content);
            }
        }
    }

    private static void validateImage(String mime, byte[] content) {
        if (!Set.of("image/png", "image/jpeg").contains(mime)) {
            throw OpenAiRequestException.unsupported("input", "LongCat image upload supports PNG and JPEG");
        }
        MediaInputValidation.requireRasterImage(mime, content);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("LongCat image is invalid");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                    throw new IllegalArgumentException("LongCat image has invalid dimensions");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("LongCat image has invalid PNG/JPEG metadata", error);
        }
    }

    private static String inlineSource(JsonNode value) {
        if (value.isTextual()) return value.asText("").trim();
        if (value.isObject()) {
            for (var field : SOURCE_FIELDS) {
                var source = inlineSource(value.path(field));
                if (source.startsWith("data:")) return source;
            }
        }
        return "";
    }
}
