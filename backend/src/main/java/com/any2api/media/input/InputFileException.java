package com.any2api.media.input;

public final class InputFileException extends RuntimeException {
    private final int status;
    private final String code;

    private InputFileException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static InputFileException missing() {
        return new InputFileException(404, "file_not_found", "image file is unavailable");
    }
    public static InputFileException conflict() {
        return new InputFileException(409, "file_idempotency_conflict", "upload idempotency key belongs to different content");
    }
    public static InputFileException tooLarge() {
        return new InputFileException(413, "file_too_large", "image file exceeds the upload limit");
    }
    public static InputFileException capacity() {
        return new InputFileException(429, "file_storage_limit", "temporary image storage is at capacity");
    }
    public static InputFileException busy() {
        return new InputFileException(429,"file_operation_busy","image upload or resolution capacity is busy");
    }
    public static InputFileException timeout() {
        return new InputFileException(408,"file_upload_timeout","image upload did not finish within its deadline");
    }
}
