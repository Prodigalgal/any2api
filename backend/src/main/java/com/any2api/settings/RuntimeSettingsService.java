package com.any2api.settings;

import com.any2api.config.Any2ApiProperties;
import com.any2api.credential.SecretCipher;
import com.any2api.lifecycle.RegistrationCaptchaPolicy;
import com.any2api.lifecycle.RegistrationProxyPolicy;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RuntimeSettingsService {
    private static final String TEMP_MAIL = "TEMP_MAIL";
    private static final String REGISTRATION_DEFAULTS = "REGISTRATION_DEFAULTS";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    private final Any2ApiProperties properties;

    public RuntimeSettingsService(
        JdbcClient jdbc,
        ObjectMapper mapper,
        SecretCipher cipher,
        Any2ApiProperties properties
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public SettingsView get() {
        return new SettingsView(tempMail(), registrationDefaults());
    }

    @Transactional(readOnly = true)
    public TempMailSettings tempMail() {
        var configured = load(TEMP_MAIL, TempMailSettings.class);
        if (configured != null) return configured.normalized();
        var fallback = properties.getTempMail();
        return new TempMailSettings(
            fallback.getApiBase(), fallback.getAdminPassword(), fallback.getSitePassword(),
            fallback.getDomains(), fallback.getPollSeconds(), fallback.getMessageTimeoutSeconds(),
            fallback.getRequestTimeoutSeconds()).normalized();
    }

    @Transactional
    public TempMailSettings updateTempMail(TempMailSettings request) {
        var value = request.normalized();
        save(TEMP_MAIL, value);
        return value;
    }

    @Transactional(readOnly = true)
    public RegistrationDefaults registrationDefaults() {
        var configured = load(REGISTRATION_DEFAULTS, RegistrationDefaults.class);
        return (configured == null ? RegistrationDefaults.standard() : configured).normalized();
    }

    @Transactional
    public RegistrationDefaults updateRegistrationDefaults(RegistrationDefaults request) {
        var value = request.normalized();
        save(REGISTRATION_DEFAULTS, value);
        return value;
    }

    public void applyMailSettings(Map<String, Object> payload, String domainOverride) {
        var current = tempMail();
        var mail = new LinkedHashMap<String, Object>();
        mail.put("base_url", current.apiBase());
        mail.put("admin_password", current.adminPassword());
        mail.put("site_password", current.sitePassword());
        mail.put("domains", current.domains());
        mail.put("poll_seconds", current.pollSeconds());
        mail.put("message_timeout_seconds", current.messageTimeoutSeconds());
        mail.put("request_timeout_seconds", current.requestTimeoutSeconds());
        if (domainOverride != null && !domainOverride.isBlank()) {
            var normalized = domainOverride.trim().toLowerCase();
            if (!current.domains().contains(normalized)) {
                throw new IllegalArgumentException("registration mail domain is not configured");
            }
            mail.put("domain", normalized);
        }
        payload.put("mail", mail);
    }

    private <T> T load(String key, Class<T> type) {
        return jdbc.sql("""
            SELECT encrypted_value, nonce FROM system_settings WHERE setting_key = :key
            """).param("key", key).query((row, ignored) -> {
                var plaintext = cipher.open(
                    row.getBytes("encrypted_value"), row.getBytes("nonce"), aad(key));
                return mapper.readValue(plaintext, type);
            }).optional().orElse(null);
    }

    private void save(String key, Object value) {
        var sealed = cipher.seal(mapper.writeValueAsBytes(value), aad(key));
        jdbc.sql("""
            INSERT INTO system_settings(setting_key, encrypted_value, nonce, key_version, updated_at)
            VALUES (:key, :encrypted, :nonce, :version, CURRENT_TIMESTAMP)
            ON CONFLICT (setting_key) DO UPDATE SET
                encrypted_value = EXCLUDED.encrypted_value,
                nonce = EXCLUDED.nonce,
                key_version = EXCLUDED.key_version,
                updated_at = CURRENT_TIMESTAMP
            """)
            .param("key", key).param("encrypted", sealed.encrypted())
            .param("nonce", sealed.nonce()).param("version", sealed.keyVersion()).update();
    }

    private static String aad(String key) {
        return "any2api:system-setting:" + key;
    }

    public record SettingsView(
        TempMailSettings tempMail,
        RegistrationDefaults registrationDefaults
    ) {}

    public record TempMailSettings(
        String apiBase,
        String adminPassword,
        String sitePassword,
        List<String> domains,
        double pollSeconds,
        int messageTimeoutSeconds,
        int requestTimeoutSeconds
    ) {
        public TempMailSettings normalized() {
            var base = apiBase == null ? "" : apiBase.trim();
            if (!base.isBlank()) {
                var uri = URI.create(base);
                if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException("temporary mail API must be an HTTP URL");
                }
                base = base.replaceAll("/+$", "");
            }
            var normalizedDomains = (domains == null ? List.<String>of() : domains).stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase())
                .filter(value -> value.matches("[a-z0-9.-]+\\.[a-z]{2,}"))
                .distinct().toList();
            if (pollSeconds < 1 || pollSeconds > 60) {
                throw new IllegalArgumentException("mail poll interval must be between 1 and 60 seconds");
            }
            if (messageTimeoutSeconds < 30 || messageTimeoutSeconds > 1800) {
                throw new IllegalArgumentException("mail wait timeout must be between 30 and 1800 seconds");
            }
            if (requestTimeoutSeconds < 5 || requestTimeoutSeconds > 300) {
                throw new IllegalArgumentException("mail request timeout must be between 5 and 300 seconds");
            }
            return new TempMailSettings(base, clean(adminPassword), clean(sitePassword),
                normalizedDomains, pollSeconds, messageTimeoutSeconds, requestTimeoutSeconds);
        }

        private static String clean(String value) { return value == null ? "" : value; }
    }

    public record RegistrationDefaults(
        int target,
        int maxAttempts,
        int concurrency,
        int attemptIntervalSeconds,
        int roundIntervalSeconds,
        int attemptTimeoutSeconds,
        int flowMaxAttempts,
        int maxConsecutiveFailureBatches,
        RegistrationProxyPolicy proxyPolicy,
        boolean headless,
        boolean aiCaptchaEnabled,
        RegistrationCaptchaPolicy.AiMode aiCaptchaMode
    ) {
        public static RegistrationDefaults standard() {
            return new RegistrationDefaults(1, 3, 1, 0, 5, 2100, 3, 5,
                RegistrationProxyPolicy.PROVIDER_DEFAULT, true, true,
                RegistrationCaptchaPolicy.AiMode.INTERNAL);
        }

        public RegistrationDefaults normalized() {
            requireRange(target, 1, 1000, "target");
            requireRange(maxAttempts, target, Math.min(10_000, target * 10), "max attempts");
            requireRange(concurrency, 1, 8, "concurrency");
            requireRange(attemptIntervalSeconds, 0, 3600, "attempt interval");
            requireRange(roundIntervalSeconds, 0, 86400, "round interval");
            requireRange(attemptTimeoutSeconds, 60, 3600, "attempt timeout");
            requireRange(flowMaxAttempts, 1, 10, "flow max attempts");
            requireRange(maxConsecutiveFailureBatches, 1, 20,
                "maximum consecutive failed rounds");
            return new RegistrationDefaults(
                target, maxAttempts, concurrency, attemptIntervalSeconds,
                roundIntervalSeconds, attemptTimeoutSeconds, flowMaxAttempts,
                maxConsecutiveFailureBatches,
                proxyPolicy == null ? RegistrationProxyPolicy.PROVIDER_DEFAULT : proxyPolicy,
                headless, aiCaptchaEnabled,
                aiCaptchaMode == null ? RegistrationCaptchaPolicy.AiMode.INTERNAL : aiCaptchaMode);
        }

        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " is outside allowed range");
            }
        }
    }
}
