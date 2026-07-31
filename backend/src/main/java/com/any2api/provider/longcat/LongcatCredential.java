package com.any2api.provider.longcat;

import com.any2api.account.LeasedProviderAccount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

record LongcatCredential(String cookie) {
    static LongcatCredential from(LeasedProviderAccount account) {
        var value = account.credential();
        var raw = value.path("cookie").asText("").trim();
        if (!raw.isBlank()) return new LongcatCredential(raw);
        var parts = new ArrayList<String>();
        add(parts, "passport_token_key", value.path("passport_token_key").asText(
            value.path("passport_token").asText("")));
        add(parts, "_lxsdk_cuid", value.path("_lxsdk_cuid").asText(
            value.path("lxsdk_cuid").asText("")));
        add(parts, "_lxsdk_s", value.path("_lxsdk_s").asText(
            value.path("lxsdk_s").asText("")));
        if (parts.stream().noneMatch(part -> part.startsWith("passport_token_key="))) {
            throw new IllegalStateException("LongCat account credential requires passport_token_key");
        }
        return new LongcatCredential(String.join("; ", parts));
    }

    private static void add(ArrayList<String> output, String name, String value) {
        if (value != null && !value.isBlank()) output.add(name + "=" + value.trim());
    }

    Map<String, String> cookies() {
        var values = new LinkedHashMap<String, String>();
        for (var part : cookie.split(";")) {
            var separator = part.indexOf('=');
            if (separator < 1) continue;
            var name = part.substring(0, separator).trim();
            var value = part.substring(separator + 1).trim();
            if (!name.isBlank() && !value.isBlank()) values.put(name, value);
        }
        return Map.copyOf(values);
    }
}
