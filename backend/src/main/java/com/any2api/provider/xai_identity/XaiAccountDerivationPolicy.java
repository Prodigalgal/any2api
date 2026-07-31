package com.any2api.provider.xai_identity;

import com.any2api.account.AccountDerivationPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class XaiAccountDerivationPolicy implements AccountDerivationPolicy {
    private static final String SOURCE_PROVIDER = "grok";
    private static final List<String> SSO_CHANNELS = List.of("grok_web", "grok_console");
    private static final List<String> SHARED_FIELDS = List.of(
        "sso", "sso-rw", "sso_rw", "sso_token", "cloudflare_cookies", "cf_cookies",
        "user_agent");

    private final ObjectMapper mapper;

    public XaiAccountDerivationPolicy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DerivationPlan derive(AccountSeed source) {
        if (!SOURCE_PROVIDER.equals(source.providerId())) return DerivationPlan.none();
        var sso = first(source, "sso", "sso-rw", "sso_rw", "sso_token");
        if (sso.isBlank()) return DerivationPlan.none();

        var identityGroup = UUID.nameUUIDFromBytes(
            ("xai:" + source.externalId()).getBytes(StandardCharsets.UTF_8)).toString();
        var sharedMetadata = new LinkedHashMap<String, Object>();
        sharedMetadata.put("identity_group_id", identityGroup);
        sharedMetadata.put("identity_source_provider", SOURCE_PROVIDER);
        var projectedCredential = mapper.createObjectNode();
        for (var field : SHARED_FIELDS) {
            var value = source.credential().path(field).asText("").trim();
            if (!value.isBlank()) projectedCredential.put(field, value);
        }
        projectedCredential.put("sso", sso);
        if (!projectedCredential.hasNonNull("sso-rw")) projectedCredential.put("sso-rw", sso);

        var derived = new ArrayList<DerivedAccount>();
        for (var providerId : SSO_CHANNELS) {
            var metadata = new LinkedHashMap<String, Object>(sharedMetadata);
            metadata.put("derived_from_provider", SOURCE_PROVIDER);
            derived.add(new DerivedAccount(providerId, source.externalId(), metadata,
                projectedCredential.deepCopy()));
        }
        return new DerivationPlan(Map.copyOf(sharedMetadata), List.copyOf(derived));
    }

    private String first(AccountSeed source, String... fields) {
        for (var field : fields) {
            var value = source.credential().path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
