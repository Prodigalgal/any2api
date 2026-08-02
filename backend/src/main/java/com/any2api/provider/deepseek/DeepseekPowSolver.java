package com.any2api.provider.deepseek;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;

final class DeepseekPowSolver {
    private static final String ALGORITHM = "DeepSeekHashV1";
    private static final int MAX_DIFFICULTY = 2_000_000;

    int solve(Challenge challenge) {
        validate(challenge);
        var prefix = (challenge.salt() + "_" + challenge.expireAt() + "_")
            .getBytes(StandardCharsets.UTF_8);
        var digest = sha3();
        var target = HexFormat.of().parseHex(challenge.challenge());
        for (var answer = 0; answer < challenge.difficulty(); answer++) {
            digest.update(prefix);
            var hash = digest.digest(Integer.toString(answer).getBytes(StandardCharsets.UTF_8));
            if (MessageDigest.isEqual(hash, target)) {
                return answer;
            }
        }
        throw new IllegalStateException("DeepSeek POW challenge has no solution in its search range");
    }

    static Challenge parse(JsonNode root, String field) {
        var value = root.path("data").path("biz_data").path(field);
        if (!value.isObject()) {
            throw new DeepseekUpstreamException(502,
                "DeepSeek POW response is missing " + field);
        }
        return new Challenge(
            value.path("algorithm").asText(""),
            value.path("challenge").asText(""),
            value.path("salt").asText(""),
            value.path("signature").asText(""),
            value.path("difficulty").asInt(0),
            value.path("expire_at").asLong(0),
            value.path("expire_after").asLong(0),
            value.path("target_path").asText(""));
    }

    private void validate(Challenge value) {
        if (!ALGORITHM.equals(value.algorithm())) {
            throw new IllegalArgumentException("unsupported DeepSeek POW algorithm");
        }
        if (!value.challenge().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("DeepSeek POW challenge must be a SHA3-256 digest");
        }
        if (value.salt().isBlank() || value.signature().isBlank()
            || value.targetPath().isBlank()) {
            throw new IllegalArgumentException("DeepSeek POW challenge is incomplete");
        }
        if (value.difficulty() < 1 || value.difficulty() > MAX_DIFFICULTY) {
            throw new IllegalArgumentException("DeepSeek POW difficulty is outside the safe range");
        }
        if (value.expireAt() > 0 && value.expireAt() <= Instant.now().toEpochMilli()) {
            throw new IllegalStateException("DeepSeek POW challenge has expired");
        }
    }

    private MessageDigest sha3() {
        try {
            return MessageDigest.getInstance("SHA3-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM does not provide SHA3-256", error);
        }
    }

    record Challenge(
        String algorithm,
        String challenge,
        String salt,
        String signature,
        int difficulty,
        long expireAt,
        long expireAfter,
        String targetPath
    ) {}
}
