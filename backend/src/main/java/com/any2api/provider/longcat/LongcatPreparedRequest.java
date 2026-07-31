package com.any2api.provider.longcat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

record LongcatPreparedRequest(
    String content,
    String agentId,
    boolean reasonEnabled,
    boolean searchEnabled,
    LongcatToolProtocol.Plan toolPlan,
    ObjectMapper mapper
) {
    ObjectNode chatBody(String conversationId) {
        var body = mapper.createObjectNode()
            .put("content", content)
            .put("conversationId", conversationId)
            .put("agentId", agentId)
            .put("reasonEnabled", reasonEnabled ? 1 : 0)
            .put("searchEnabled", searchEnabled ? 1 : 0)
            .put("regenerate", 0)
            .put("parentMessageId", 0);
        body.set("files", mapper.createArrayNode());
        return body;
    }
}
