package com.any2api.provider.mimo;

import java.util.List;
record MimoPreparedRequest(
    List<MimoTool> tools,
    boolean toolRequired,
    boolean parallelToolCalls,
    List<MimoMediaSource> media
) {
}
