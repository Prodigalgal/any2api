package com.any2api.provider.minmax;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

record MinmaxPreparedRequest(
    String content,
    ObjectNode model,
    String agentRole,
    boolean enableTeam,
    boolean worktreeMode,
    List<MinmaxMediaSource> media
) {
    MinmaxPreparedRequest {
        media = media == null ? List.of() : List.copyOf(media);
    }

    String sessionModel() {
        var providerId = model.path("provider_id").asText("").trim();
        var modelId = model.path("model_id").asText("").trim();
        if (providerId.isBlank() || modelId.isBlank()) {
            throw new IllegalStateException("MinMax prepared model is incomplete");
        }
        return providerId + "/" + modelId;
    }
}
