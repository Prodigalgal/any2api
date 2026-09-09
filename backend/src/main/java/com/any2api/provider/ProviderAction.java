package com.any2api.provider;

import java.util.Locale;

/**
 * Stable business-action vocabulary shared by the Java gateway and automation channels.
 * Provider-specific URLs, headers and signatures remain inside the selected channel.
 */
public enum ProviderAction {
    REGISTER("register", "register"),
    REAUTHENTICATE("reauthenticate", "reauthenticate"),
    KEEPALIVE("keepalive", "keepalive"),
    DAILY_CHECKIN("daily_checkin", "daily_checkin"),
    MODEL_DISCOVERY("model_discovery", "models"),
    CHAT("chat", "chat"),
    PROVIDER_QUERY("provider_query", "agents"),
    MEDIA_POLICY("media_policy", "files_policy"),
    MEDIA_CALLBACK("media_callback", "files_callback"),
    RAW_REQUEST("raw_request", null);

    private final String externalName;
    private final String legacyOperation;

    ProviderAction(String externalName, String legacyOperation) {
        this.externalName = externalName;
        this.legacyOperation = legacyOperation;
    }

    public String externalName() {
        return externalName;
    }

    public String legacyOperation() {
        return legacyOperation;
    }

    public static ProviderAction fromLegacyOperation(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "register" -> REGISTER;
            case "reauthenticate" -> REAUTHENTICATE;
            case "keepalive" -> KEEPALIVE;
            case "daily_checkin" -> DAILY_CHECKIN;
            case "models" -> MODEL_DISCOVERY;
            case "chat" -> CHAT;
            case "agents" -> PROVIDER_QUERY;
            case "files_policy" -> MEDIA_POLICY;
            case "files_callback" -> MEDIA_CALLBACK;
            default -> RAW_REQUEST;
        };
    }

    public static ProviderAction fromExternalName(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (var action : values()) {
            if (action.externalName.equals(normalized)) return action;
        }
        throw new IllegalArgumentException("unsupported provider action: " + value);
    }
}
