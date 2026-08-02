package com.any2api.config;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class SecuritySettingsValidator {
    private static final int MINIMUM_ADMIN_PASSWORD_LENGTH = 8;

    private final Any2ApiProperties properties;

    public SecuritySettingsValidator(Any2ApiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        var security = properties.getSecurity();
        requireLength(
            "ANY2API_ADMIN_PASSWORD",
            security.getAdminPassword(),
            MINIMUM_ADMIN_PASSWORD_LENGTH);
        requireLength("ANY2API_PUBLIC_API_KEY", security.getPublicApiKey(), 24);
        requireLength("ANY2API_INTERNAL_TOKEN", security.getInternalToken(), 24);
        if (decodeKey(security.getCredentialMasterKey()).length != 32) {
            throw new IllegalStateException(
                "ANY2API_CREDENTIAL_MASTER_KEY must be Base64 for exactly 32 bytes");
        }
    }

    private void requireLength(String name, String value, int minimum) {
        if (value == null || value.length() < minimum) {
            throw new IllegalStateException(name + " must contain at least " + minimum + " characters");
        }
    }

    private byte[] decodeKey(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException error) {
                return new byte[0];
            }
        }
    }
}
