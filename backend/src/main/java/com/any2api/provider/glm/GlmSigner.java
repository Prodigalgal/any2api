package com.any2api.provider.glm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class GlmSigner {
    private final GlmProperties properties;

    GlmSigner(GlmProperties properties) { this.properties = properties; }

    SignedRequest sign(String requestId, String userId, String prompt, long timestamp) {
        var sortedPayload = "requestId," + requestId + ",timestamp," + timestamp
            + ",user_id," + userId;
        var promptBase64 = Base64.getEncoder().encodeToString(
            prompt.getBytes(StandardCharsets.UTF_8));
        var bucket = Math.floorDiv(timestamp, 5 * 60 * 1000);
        var rotatingKey = hmac(properties.getSignatureKey(), Long.toString(bucket));
        var message = sortedPayload + "|" + promptBase64 + "|" + timestamp;
        return new SignedRequest(hmac(rotatingKey, message), timestamp);
    }

    private String hmac(String key, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("GLM signature runtime is unavailable", error);
        }
    }

    record SignedRequest(String signature, long timestamp) {}
}
