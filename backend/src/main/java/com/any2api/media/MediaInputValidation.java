package com.any2api.media;

import java.nio.charset.StandardCharsets;

public final class MediaInputValidation {
    private MediaInputValidation() {}

    public static String requireRasterImage(String declaredType, byte[] content) {
        var detected = detect(content);
        if (detected == null) {
            throw new IllegalArgumentException(
                "image file must be PNG, JPEG, WebP, GIF, or AVIF");
        }
        var declared = declaredType == null ? "" : declaredType.split(";", 2)[0]
            .trim().toLowerCase();
        if (!declared.isBlank() && !"application/octet-stream".equals(declared)
            && !declared.equals(detected)) {
            throw new IllegalArgumentException(
                "image file content does not match its declared content type");
        }
        return detected;
    }

    private static String detect(byte[] value) {
        if (starts(value, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) {
            return "image/png";
        }
        if (value.length >= 3 && (value[0] & 0xff) == 0xff
            && (value[1] & 0xff) == 0xd8 && (value[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (value.length >= 12 && ascii(value, 0, 4).equals("RIFF")
            && ascii(value, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        if (value.length >= 6 && (ascii(value, 0, 6).equals("GIF87a")
            || ascii(value, 0, 6).equals("GIF89a"))) {
            return "image/gif";
        }
        if (value.length >= 12 && ascii(value, 4, 4).equals("ftyp")) {
            var brand = ascii(value, 8, 4);
            if (brand.equals("avif") || brand.equals("avis")) return "image/avif";
        }
        return null;
    }

    private static boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (var index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static String ascii(byte[] value, int offset, int length) {
        return new String(value, offset, length, StandardCharsets.US_ASCII);
    }
}
