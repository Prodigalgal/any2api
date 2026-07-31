package com.any2api.provider;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

public final class ProviderExecutionContext {
    private final String requestId;
    private final UUID accountId;
    private final String credentialVersion;
    private final String leaseOwnerToken;
    private final long fencingToken;
    private final Instant deadline;
    private final AtomicReference<JsonNode> credentialPatch =
        new AtomicReference<>(MissingNode.getInstance());

    public ProviderExecutionContext(
        String requestId,
        UUID accountId,
        String credentialVersion,
        String leaseOwnerToken,
        long fencingToken,
        Instant deadline
    ) {
        this.requestId = requestId;
        this.accountId = accountId;
        this.credentialVersion = credentialVersion;
        this.leaseOwnerToken = leaseOwnerToken;
        this.fencingToken = fencingToken;
        this.deadline = deadline;
    }

    public String requestId() { return requestId; }
    public UUID accountId() { return accountId; }
    public String credentialVersion() { return credentialVersion; }
    public String leaseOwnerToken() { return leaseOwnerToken; }
    public long fencingToken() { return fencingToken; }
    public Instant deadline() { return deadline; }

    public void acceptCredentialPatch(JsonNode patch) {
        if (patch != null && patch.isObject() && !patch.isEmpty()) {
            credentialPatch.set(patch.deepCopy());
        }
    }

    public JsonNode credentialPatch() { return credentialPatch.get().deepCopy(); }
}
