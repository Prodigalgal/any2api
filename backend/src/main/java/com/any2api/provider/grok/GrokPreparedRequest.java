package com.any2api.provider.grok;

import tools.jackson.databind.node.ObjectNode;

record GrokPreparedRequest(ObjectNode body, String conversationId) {
}
