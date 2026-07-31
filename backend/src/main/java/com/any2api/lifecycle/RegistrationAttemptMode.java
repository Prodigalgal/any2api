package com.any2api.lifecycle;

import java.util.Arrays;

public enum RegistrationAttemptMode {
    NEW_IDENTITY("new_identity"),
    SINGLE_IDENTITY("single_identity");

    private final String externalName;

    RegistrationAttemptMode(String externalName) {
        this.externalName = externalName;
    }

    static RegistrationAttemptMode fromExternalName(String value) {
        return Arrays.stream(values())
            .filter(mode -> mode.externalName.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unsupported registration attempt mode: " + value));
    }
}
