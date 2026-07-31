package com.any2api.provider.grok_web;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class GrokWebStatsigSigner {
    private static final long EPOCH_SECONDS = 1_682_924_400L;
    private static final String HASH_SALT = "obfiowerehiring";
    private static final Pattern META_NAME_FIRST = Pattern.compile(
        "<meta[^>]+name=[\\\"']grok-site[-\\x{2010}-\\x{2015}]verification[\\\"']"
            + "[^>]+content=[\\\"']([^\\\"']+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT_FIRST = Pattern.compile(
        "<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+name=[\\\"']"
            + "grok-site[-\\x{2010}-\\x{2015}]verification[\\\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CURVES = Pattern.compile(
        "\\\\\"curves\\\\\":(\\[\\[.*?\\]\\]),\\\\\"css_class\\\\\":"
            + "\\\\\"[A-Za-z0-9_-]{1,64}\\\\\"",
        Pattern.DOTALL);

    private final ObjectMapper mapper;
    private final GrokWebProperties properties;
    private final SecureRandom random = new SecureRandom();

    GrokWebStatsigSigner(ObjectMapper mapper, GrokWebProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    String sign(String method, String path, String html) {
        var manual = properties.getStatsigManualValue().trim();
        if (!manual.isBlank()) return validate(manual);
        return sign(method, path, parse(html),
            Instant.now().getEpochSecond() - EPOCH_SECONDS, random.nextInt(256));
    }

    StatsigEnvironment parse(String html) {
        byte[] verification;
        try {
            verification = Base64.getDecoder().decode(extractMeta(html));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                "Grok Web site verification metadata is invalid", error);
        }
        var matched = CURVES.matcher(html);
        if (!matched.find()) {
            throw new IllegalArgumentException("Grok Web site verification curves are missing");
        }
        JsonNode raw;
        try {
            raw = mapper.readTree(matched.group(1).replace("\\\"", "\""));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                "Grok Web site verification curves are invalid", error);
        }
        if (verification.length != 48 || !raw.isArray() || raw.size() != 4) {
            throw new IllegalArgumentException(
                "Grok Web site verification environment has an unsupported shape");
        }
        var groups = new ArrayList<List<Curve>>(4);
        for (var group : raw) {
            if (!group.isArray() || group.size() < 16) {
                throw new IllegalArgumentException(
                    "Grok Web site verification environment has an unsupported shape");
            }
            var curves = new ArrayList<Curve>(group.size());
            for (var curve : group) curves.add(curve(curve));
            groups.add(List.copyOf(curves));
        }
        return new StatsigEnvironment(verification, List.copyOf(groups));
    }

    String sign(
        String method,
        String path,
        StatsigEnvironment environment,
        long timestamp,
        int randomByte
    ) {
        if (timestamp < 0 || timestamp > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Grok Web Statsig timestamp is outside uint32");
        }
        if (randomByte < 0 || randomByte > 255) {
            throw new IllegalArgumentException("Grok Web Statsig random byte is outside uint8");
        }
        var style = styleToken(environment);
        var material = method.toUpperCase() + "!" + path + "!" + timestamp
            + HASH_SALT + style;
        var digest = digest(material.getBytes(StandardCharsets.UTF_8));
        var raw = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) randomByte)
            .put(environment.verification())
            .putInt((int) timestamp)
            .put(digest, 0, 16)
            .put((byte) 3)
            .array();
        for (var index = 1; index < raw.length; index++) raw[index] ^= (byte) randomByte;
        return Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    static String extractMeta(String body) {
        var nameFirst = META_NAME_FIRST.matcher(body);
        if (nameFirst.find()) return nameFirst.group(1).trim();
        var contentFirst = META_CONTENT_FIRST.matcher(body);
        if (contentFirst.find()) return contentFirst.group(1).trim();
        throw new IllegalArgumentException(
            "Grok Web index has no site verification metadata");
    }

    private String styleToken(StatsigEnvironment environment) {
        var verification = environment.verification();
        var group = Byte.toUnsignedInt(verification[5]) % 4;
        var index = Byte.toUnsignedInt(verification[2]) % 16;
        var curve = environment.curves().get(group).get(index);
        var currentTime = Math.round(
            (Byte.toUnsignedInt(verification[1]) % 16)
                * (Byte.toUnsignedInt(verification[20]) % 16)
                * (Byte.toUnsignedInt(verification[19]) % 16) / 10.0) * 10.0;
        var control = new double[4];
        for (var offset = 0; offset < control.length; offset++) {
            control[offset] = scale(curve.bezier()[offset], offset % 2 == 1 ? -1 : 0, 1);
        }
        var eased = cubicBezier(currentTime / 4096.0,
            control[0], control[1], control[2], control[3]);
        var values = new double[9];
        for (var offset = 0; offset < 3; offset++) {
            values[offset] = Math.round(curve.color()[offset]
                + (curve.color()[offset + 3] - curve.color()[offset]) * eased);
        }
        var radians = Math.floor(scale(curve.degrees(), 60, 360))
            * eased * Math.PI / 180.0;
        values[3] = Math.cos(radians);
        values[4] = Math.sin(radians);
        values[5] = -Math.sin(radians);
        values[6] = Math.cos(radians);
        values[7] = 0;
        values[8] = 0;
        var token = new StringBuilder();
        for (var value : values) token.append(numberToHex(value));
        return token.toString().replace(".", "").replace("-", "");
    }

    private Curve curve(JsonNode source) {
        return new Curve(
            integers(source.path("color"), 6),
            source.path("deg").asInt(),
            integers(source.path("bezier"), 4));
    }

    private int[] integers(JsonNode source, int size) {
        if (!source.isArray() || source.size() != size) {
            throw new IllegalArgumentException(
                "Grok Web Statsig curve has an unsupported shape");
        }
        var values = new int[size];
        for (var index = 0; index < size; index++) values[index] = source.get(index).asInt();
        return values;
    }

    private static double scale(int value, int start, int end) {
        return Math.rint((value * (end - start) / 255.0 + start) * 100.0) / 100.0;
    }

    private static double cubicBezier(
        double target,
        double x1,
        double y1,
        double x2,
        double y2
    ) {
        var low = 0.0;
        var high = 1.0;
        for (var iteration = 0; iteration < 80; iteration++) {
            var middle = (low + high) / 2.0;
            if (bezierPoint(middle, x1, x2) < target) low = middle;
            else high = middle;
        }
        return bezierPoint((low + high) / 2.0, y1, y2);
    }

    private static double bezierPoint(double value, double first, double second) {
        var inverse = 1.0 - value;
        return 3 * inverse * inverse * value * first
            + 3 * inverse * value * value * second
            + value * value * value;
    }

    private static String numberToHex(double value) {
        var rounded = Math.rint(value * 100.0) / 100.0;
        if (rounded == 0) return "0";
        var sign = rounded < 0 ? "-" : "";
        var absolute = Math.abs(rounded);
        var integer = (long) Math.floor(absolute);
        var fraction = absolute - integer;
        if (fraction == 0) return sign + Long.toHexString(integer);
        var digits = new StringBuilder();
        for (var index = 0; index < 20; index++) {
            fraction *= 16;
            var digit = (int) Math.floor(fraction + 1e-12);
            digits.append(Integer.toHexString(digit));
            fraction -= digit;
            if (Math.abs(fraction) < 1e-12) break;
        }
        while (!digits.isEmpty() && digits.charAt(digits.length() - 1) == '0') {
            digits.deleteCharAt(digits.length() - 1);
        }
        return sign + Long.toHexString(integer) + (digits.isEmpty() ? "" : "." + digits);
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String validate(String value) {
        var trimmed = value.trim();
        try {
            if (Base64.getDecoder().decode(trimmed).length == 70) return trimmed;
        } catch (IllegalArgumentException ignored) {
            // Return a bounded validation error without exposing the configured value.
        }
        throw new IllegalArgumentException("invalid Grok Web Statsig signature");
    }

    record StatsigEnvironment(byte[] verification, List<List<Curve>> curves) {
        StatsigEnvironment {
            verification = verification.clone();
        }

        @Override public byte[] verification() { return verification.clone(); }
    }

    record Curve(int[] color, int degrees, int[] bezier) {}
}
