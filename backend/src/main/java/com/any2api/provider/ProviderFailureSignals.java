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

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
