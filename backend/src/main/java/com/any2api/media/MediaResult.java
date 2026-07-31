package com.any2api.media;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

public record MediaResult(List<GeneratedMedia> items, JsonNode credentialPatch) {
    public MediaResult {
        items = List.copyOf(items);
        credentialPatch = credentialPatch == null
            ? MissingNode.getInstance() : credentialPatch.deepCopy();
    }

    public MediaResult(List<GeneratedMedia> items) {
        this(items, MissingNode.getInstance());
    }

    @Override public JsonNode credentialPatch() { return credentialPatch.deepCopy(); }
}
