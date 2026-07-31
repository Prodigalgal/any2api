package com.any2api.lifecycle;

import java.util.Arrays;

public enum AutomationOperation {
    REGISTER("register"),
    REAUTHENTICATE("reauthenticate"),
    KEEPALIVE("keepalive");

    private final String externalName;

    AutomationOperation(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static AutomationOperation fromExternalName(String value) {
        return Arrays.stream(values())
            .filter(operation -> operation.externalName.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unsupported automation operation: " + value));
    }
}
