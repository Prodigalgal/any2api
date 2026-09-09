package com.any2api.provider;

import java.util.Locale;

/**
 * Inference transport selected after the provider-level policy is resolved.
 * AUTO is a policy value and is never sent to the automation transport.
 */
public enum ProviderTransportMode {
    API("api"),
    RUNTIME("camoufox_browser_runtime"),
    AUTO("auto");

    private final String externalName;

    ProviderTransportMode(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static ProviderTransportMode parse(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "api" -> API;
            case "runtime", "camoufox_browser_runtime" -> RUNTIME;
            case "auto" -> AUTO;
            default -> throw new IllegalArgumentException(
                "unsupported provider transport mode: " + value);
        };
    }
}
