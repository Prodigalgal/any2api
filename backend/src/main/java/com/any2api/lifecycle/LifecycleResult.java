package com.any2api.lifecycle;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

public record LifecycleResult(
    boolean healthy,
    boolean authExpired,
    boolean terminal,
    String errorClass,
    JsonNode credentialPatch,
    JsonNode metadataPatch,
    Instant credentialExpiresAt
) {
    public static LifecycleResult fromAutomation(JsonNode result) {
        return new LifecycleResult(
            result.path("healthy").asBoolean(false),
            result.path("auth_expired").asBoolean(false),
            result.path("terminal").asBoolean(false),
            result.path("error_class").asText(""),
            objectOrMissing(result.path("credential_patch")),
            objectOrMissing(result.path("metadata_patch")),
            instant(result.path("credential_expires_at").asText("")));
    }

    public static LifecycleResult healthy(JsonNode credentialPatch, JsonNode metadataPatch) {
        return new LifecycleResult(true, false, false, "",
            objectOrMissing(credentialPatch), objectOrMissing(metadataPatch), null);
    }

    public static LifecycleResult failed(
        boolean authExpired,
        boolean terminal,
        String errorClass,
        JsonNode credentialPatch
    ) {
        return new LifecycleResult(false, authExpired, terminal, errorClass,
            objectOrMissing(credentialPatch), MissingNode.getInstance(), null);
    }

    private static JsonNode objectOrMissing(JsonNode value) {
        return value != null && value.isObject() ? value : MissingNode.getInstance();
    }

    private static Instant instant(String value) {
        try { return value == null || value.isBlank() ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }
}
