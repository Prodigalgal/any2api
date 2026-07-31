package com.any2api.media;

public record GeneratedMedia(
    String contentType,
    byte[] content,
    String revisedPrompt
) {
    public GeneratedMedia {
        content = content.clone();
    }

    @Override public byte[] content() { return content.clone(); }
}
