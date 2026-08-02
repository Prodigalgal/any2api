package com.any2api.protocol;

public final class OpenAiRequestException extends IllegalArgumentException {
    private final String type;
    private final String parameter;

    private OpenAiRequestException(String type, String parameter, String message) {
        super(message);
        this.type = type;
        this.parameter = parameter;
    }

    public String type() {
        return type;
    }

    public String parameter() {
        return parameter;
    }

    public static OpenAiRequestException invalid(String parameter, String message) {
        return new OpenAiRequestException("invalid_request_error", parameter, message);
    }

    public static OpenAiRequestException unsupported(String parameter, String message) {
        return new OpenAiRequestException("unsupported_parameter", parameter, message);
    }

    public static OpenAiRequestException unknownProviderOption(
        String parameter,
        String message
    ) {
        return new OpenAiRequestException("unknown_provider_option", parameter, message);
    }

    public static OpenAiRequestException conflict(String parameter, String message) {
        return new OpenAiRequestException("parameter_conflict", parameter, message);
    }
}
