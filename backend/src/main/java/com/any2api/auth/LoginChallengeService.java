package com.any2api.auth;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LoginChallengeService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final ReactiveStringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final byte[] signingKey;
    private final int difficulty;

    public LoginChallengeService(
        ReactiveStringRedisTemplate redis,
        Any2ApiProperties properties
    ) {
        this.redis = redis;
        var security = properties.getSecurity();
        var secret = !security.getInternalToken().isBlank()
            ? security.getInternalToken()
            : security.getAdminPassword() + ":" + security.getCredentialMasterKey();
        this.signingKey = digest(secret.getBytes(StandardCharsets.UTF_8));
        this.difficulty = Math.max(12, Math.min(24, security.getLoginPowDifficulty()));
    }

    public Mono<Challenge> issue() {
        var left = random.nextInt(8, 30);
        var multiply = random.nextBoolean();
        var right = multiply ? random.nextInt(2, 10) : random.nextInt(5, 25);
        var answer = multiply ? left * right : left + right;
        var operator = multiply ? "x" : "+";
        var id = UUID.randomUUID().toString();
        var salt = randomHex(16);
        var expiresAt = Instant.now().plus(TTL);
        var payload = id + ":" + difficulty + ":" + expiresAt.getEpochSecond() + ":" + salt;
        var encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        var token = encoded + "." + sign(encoded);
        return redis.opsForValue().set(key(id), Integer.toString(answer), TTL)
            .flatMap(stored -> stored
                ? Mono.just(new Challenge(token, left + " " + operator + " " + right,
                    difficulty, expiresAt))
                : Mono.error(new IllegalStateException("challenge store unavailable")));
    }

    public Mono<Boolean> verify(String token, String answer, long nonce) {
        var parsed = parse(token);
        if (parsed == null || parsed.expiresAt().isBefore(Instant.now())
            || !validWork(token, nonce, parsed.difficulty())) {
            return Mono.just(false);
        }
        return redis.opsForValue().getAndDelete(key(parsed.id()))
            .map(expected -> constantTime(expected, answer == null ? "" : answer.trim()))
            .defaultIfEmpty(false);
    }

    private Parsed parse(String token) {
        if (token == null) return null;
        var separator = token.lastIndexOf('.');
        if (separator < 1) return null;
        var encoded = token.substring(0, separator);
        if (!constantTime(sign(encoded), token.substring(separator + 1))) return null;
        try {
            var payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            var fields = payload.split(":", 4);
            if (fields.length != 4) return null;
            return new Parsed(fields[0], Integer.parseInt(fields[1]),
                Instant.ofEpochSecond(Long.parseLong(fields[2])));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private boolean validWork(String token, long nonce, int requiredBits) {
        var hash = digest((token + ":" + nonce).getBytes(StandardCharsets.UTF_8));
        var fullBytes = requiredBits / 8;
        var remainingBits = requiredBits % 8;
        for (var index = 0; index < fullBytes; index++) {
            if (hash[index] != 0) return false;
        }
        return remainingBits == 0
            || (hash[fullBytes] & (0xff << (8 - remainingBits))) == 0;
    }

    private String sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("challenge signing failed", error);
        }
    }

    private byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private String randomHex(int bytes) {
        var value = new byte[bytes];
        random.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }

    private boolean constantTime(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    private String key(String id) { return "any2api:admin:login-challenge:" + id; }

    public record Challenge(String challengeToken, String expression, int difficulty,
                            Instant expiresAt) {}
    private record Parsed(String id, int difficulty, Instant expiresAt) {}
}
