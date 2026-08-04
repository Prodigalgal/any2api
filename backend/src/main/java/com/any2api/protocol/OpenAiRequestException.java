package com.any2api.protocol;

import java.util.List;

public final class OpenAiRequestException extends IllegalArgumentException {
    private final String type;
    private final String parameter;
    private final List<String> acceptedParameters;

    private OpenAiRequestException(String type, String parameter, String message) {
        this(type, parameter, message, List.of());
    }

    private OpenAiRequestException(
        String type,
        String parameter,
        String message,
        List<String> acceptedParameters
    ) {
        super(message);
        this.type = type;
        this.parameter = parameter;
        this.acceptedParameters = acceptedParameters == null
            ? List.of() : List.copyOf(acceptedParameters);
    }

    public String type() {
        return type;
    }

    public String parameter() {
        return parameter;
    }

    public List<String> acceptedParameters() {
        return acceptedParameters;
    }

    public OpenAiRequestException withAcceptedParameters(List<String> accepted) {
        return new OpenAiRequestException(type, parameter, getMessage(), accepted);
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
