package com.any2api.media;

public record MediaInput(String filename, String contentType, byte[] content) {
    public MediaInput {
        content = content.clone();
    }

    @Override public byte[] content() { return content.clone(); }
}
