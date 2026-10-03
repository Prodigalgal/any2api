package com.any2api.protocol;

public final class ModelNotFoundException extends RuntimeException {
    public ModelNotFoundException() {
        super("The model does not exist or you do not have access to it");
    }
}
