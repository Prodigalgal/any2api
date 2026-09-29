package com.any2api.provider;

import java.util.Locale;
import java.util.Set;

/** Shared, conservative classification for provider verification challenges. */
public final class ProviderFailureSignals {
    private static final Set<String> ANTI_BOT_MARKERS = Set.of(
        "captcha", "recaptcha", "hcaptcha", "turnstile", "waf challenge", "x-amzn-waf",
        "bot challenge", "human verification", "verify you are human",
        "verification required", "verification challenge", "risk control",
        "risk_control", "risk-control", "h5guard");

    private static final Set<String> CREDENTIAL_REJECTED_MARKERS = Set.of(
        "unauthorized", "login expired", "token expired", "token_expired",
        "invalid token", "invalid_token", "invalid session", "session expired",
        "please log in", "please login", "not logged in", "authentication failed",
        "code=401", "code\":401", "code\": 401", "status\":401", "status\": 401",
        "\"code\":\"unauthorized\"", "\"code\": \"unauthorized\"");

    private ProviderFailureSignals() {
    }

    /**
     * Returns true for an explicit anti-bot code at any bounded HTTP status, or for
     * a known challenge marker in a client-error response. Generic 5xx bodies are
     * intentionally not classified from words such as captcha or risk alone.
     */
    public static boolean isAntiBot(int status, String message) {
        var normalized = normalize(message);
        if (normalized.contains("anti_bot_rejected")
            || normalized.contains("anti-bot")
            || normalized.contains("antibot")) {
            return true;
        }
        if (status < 400 || status >= 500) return false;
        if (normalized.contains("fail_sys_user_validate")) return true;
        return ANTI_BOT_MARKERS.stream().anyMatch(normalized::contains);
    }

    /**
     * Returns true when the HTTP status indicates bad credentials (401/403) or
     * when an error payload carries unequivocal authentication/session expiration markers.
     */
    public static boolean isCredentialRejected(int status, String message) {
        if (status == 401 || status == 403) {
            return true;
        }
        var normalized = normalize(message);
        if (normalized.contains("credential_rejected") || normalized.contains("credentials_rejected")) {
            return true;
        }
        return CREDENTIAL_REJECTED_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
