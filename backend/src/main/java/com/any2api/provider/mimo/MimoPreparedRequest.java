package com.any2api.provider.mimo;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

record MimoPreparedRequest(
    ObjectNode body,
    List<MimoTool> tools,
    boolean toolRequired,
    boolean parallelToolCalls,
    List<MimoMediaSource> media
) {
}
