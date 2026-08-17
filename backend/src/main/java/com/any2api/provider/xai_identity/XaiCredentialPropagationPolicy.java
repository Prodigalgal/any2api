package com.any2api.provider.xai_identity;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.credential.CredentialVault;
import com.any2api.lifecycle.CredentialPropagationPolicy;
import com.any2api.lifecycle.LifecycleScheduleService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class XaiCredentialPropagationPolicy implements CredentialPropagationPolicy {
    private static final String SOURCE_PROVIDER = "grok";
    private static final List<String> DERIVED_PROVIDERS = List.of("grok_web", "grok_console");
    private static final List<String> SHARED_FIELDS = List.of(
        "sso", "sso-rw", "sso_rw", "sso_token", "sso_cookie", "cookies", "cookie",
        "cloudflare_cookies", "cf_cookies", "user_agent");

    private final AccountRepository accounts;
    private final CredentialVault credentials;
    private final LifecycleScheduleService schedules;

    public XaiCredentialPropagationPolicy(
        AccountRepository accounts,
        CredentialVault credentials,
        LifecycleScheduleService schedules
    ) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.schedules = schedules;
    }

    @Override
    public void propagate(
        AccountEntity source,
        JsonNode recoveredCredential,
        Instant expiresAt
    ) {
        if (!SOURCE_PROVIDER.equals(source.getProviderId())) return;
        var identityGroup = String.valueOf(
            source.getMetadata().getOrDefault("identity_group_id", "")).trim();
        var sso = recoveredCredential.path("sso").asText("").trim();
        if (identityGroup.isBlank() || sso.isBlank()) return;

        for (var providerId : DERIVED_PROVIDERS) {
            accounts.findByProviderIdAndIdentityGroup(providerId, identityGroup)
                .ifPresent(derived -> {
                    var current = credentials.read(derived, providerId).payload();
                    var merged = (ObjectNode) current.deepCopy();
                    for (var field : SHARED_FIELDS) {
                        var value = recoveredCredential.path(field);
                        if (!value.isMissingNode() && !value.isNull()) {
                            merged.set(field, value.deepCopy());
                        }
                    }
                    merged.put("sso", sso);
                    if (recoveredCredential.path("sso-rw").asText("").isBlank()) {
                        merged.put("sso-rw", sso);
                    }
                    credentials.store(derived, providerId, merged, expiresAt);
                    schedules.scheduleRecoveryProbe(
                        derived.getId(), providerId, Duration.ofMinutes(2));
                });
        }
        source.mergeMetadata(java.util.Map.of("xai_force_sso_refresh", false));
        accounts.save(source);
    }
}
