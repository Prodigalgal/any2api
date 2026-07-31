package com.any2api.provider.qwen;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

record QwenPreparedMessage(String role, String content, List<ObjectNode> files) {
    QwenPreparedMessage {
        files = List.copyOf(files);
    }
}
