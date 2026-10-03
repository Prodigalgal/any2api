package com.any2api.provider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class ModelCatalogSnapshotCodec {
    private static final String PREFIX = "gzip:v1:";
    private static final int COMPRESSION_THRESHOLD = 16 * 1024;
    private static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;

    private ModelCatalogSnapshotCodec() {}

    static String encode(String snapshot) throws IOException {
        var bytes = snapshot.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < COMPRESSION_THRESHOLD || bytes.length > MAX_EXPANDED_BYTES) {
            return snapshot;
        }
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        var encoded = PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
        return encoded.length() < snapshot.length() ? encoded : snapshot;
    }

    static String decode(String snapshot) throws IOException {
        if (!snapshot.startsWith(PREFIX)) return snapshot;
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(snapshot.substring(PREFIX.length()));
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid compressed model catalog snapshot", error);
        }
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var expanded = gzip.readNBytes(MAX_EXPANDED_BYTES + 1);
            if (expanded.length > MAX_EXPANDED_BYTES) {
                throw new IOException("compressed model catalog snapshot exceeds expansion limit");
            }
            return new String(expanded, StandardCharsets.UTF_8);
        }
    }
}
