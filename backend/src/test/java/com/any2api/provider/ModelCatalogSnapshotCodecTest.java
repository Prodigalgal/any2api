package com.any2api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ModelCatalogSnapshotCodecTest {
    @Test
    void largeUnicodeSnapshotPreservesAllFieldsWithBoundedWireCost() throws Exception {
        var snapshot = "[{\"metadata\":\"中文 capabilities namespace custom runtime\"}]".repeat(20_000);
        var encoded = ModelCatalogSnapshotCodec.encode(snapshot);
        assertThat(encoded).startsWith("gzip:v1:").hasSizeLessThan(snapshot.length() / 10);
        assertThat(ModelCatalogSnapshotCodec.decode(encoded)).isEqualTo(snapshot);
    }

    @Test
    void smallSnapshotRetainsPlainJsonRepresentation() throws Exception {
        var snapshot = "[{\"id\":\"fixture\",\"available\":false}]";
        assertThat(ModelCatalogSnapshotCodec.encode(snapshot)).isEqualTo(snapshot);
        assertThat(ModelCatalogSnapshotCodec.decode(snapshot)).isEqualTo(snapshot);
    }

    @Test
    void damagedCompressedSnapshotFailsExplicitly() {
        assertThatThrownBy(() -> ModelCatalogSnapshotCodec.decode("gzip:v1:not-base64"))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ModelCatalogSnapshotCodec.decode("gzip:v1:YQ=="))
            .isInstanceOf(IOException.class);
    }

    @Test
    void compressedExpansionCannotExhaustHeap() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            var block = new byte[1024 * 1024];
            for (var index = 0; index < 33; index++) gzip.write(block);
        }
        var encoded = "gzip:v1:" + Base64.getEncoder().encodeToString(output.toByteArray());
        assertThatThrownBy(() -> ModelCatalogSnapshotCodec.decode(encoded))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("expansion limit");
    }
}
