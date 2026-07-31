package com.any2api.provider.mimo;

record MimoMediaSource(String kind, String dataUrl, String filename) {
    MimoMediaSource {
        if (!"image".equals(kind) && !"file".equals(kind)) {
            throw new IllegalArgumentException("unsupported MiMo media kind: " + kind);
        }
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            throw new IllegalArgumentException(
                "MiMo multimodal input currently requires an inline data URL");
        }
    }
}
