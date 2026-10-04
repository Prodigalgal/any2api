package com.any2api.coordination;

public final class CoordinationUnavailableException extends RuntimeException {
    public static final String CODE = "coordination_unavailable";
    public static final String MESSAGE = "coordination service is temporarily unavailable";

    public CoordinationUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public static boolean isCausedBy(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<Throwable, Boolean>());
        for (var cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CoordinationUnavailableException) return true;
        }
        return false;
    }
}
