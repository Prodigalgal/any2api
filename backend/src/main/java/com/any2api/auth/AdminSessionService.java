package com.any2api.auth;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionService {
    public static final String COOKIE_NAME = "any2api_admin_session";
    private final String username;
    private final String password;
    private final byte[] signingKey;
    private final long ttlSeconds;

    public AdminSessionService(Any2ApiProperties properties) {
        var security = properties.getSecurity();
        username = security.getAdminUsername();
        password = security.getAdminPassword();
        ttlSeconds = Math.max(300, security.getAdminSessionTtlSeconds());
        var secret = !security.getInternalToken().isBlank()
            ? security.getInternalToken()
            : password + ":" + security.getCredentialMasterKey();
        signingKey = sha256(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Session> authenticate(String actualUsername, String actualPassword) {
        if (!constantTime(username, actualUsername) || !constantTime(password, actualPassword)) {
            return Optional.empty();
        }
        var expiresAt = Instant.now().plusSeconds(ttlSeconds);
        var payload = username + ":" + expiresAt.getEpochSecond() + ":" + UUID.randomUUID();
        var encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Session(encoded + "." + sign(encoded), username, expiresAt));
    }

    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        var separator = token.lastIndexOf('.');
        if (separator < 1) return Optional.empty();
        var payload = token.substring(0, separator);
        if (!constantTime(sign(payload), token.substring(separator + 1))) return Optional.empty();
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            var fields = decoded.split(":", 3);
            if (fields.length != 3 || !fields[0].equals(username)
                || Instant.ofEpochSecond(Long.parseLong(fields[1])).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(fields[0]);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("admin session signing failed", error);
        }
    }

    private boolean constantTime(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    public record Session(String token, String username, Instant expiresAt) {}
}
