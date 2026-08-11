package com.any2api.provider;

import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.OpenAiRequestException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class ModelRequestLimitGuard {
    private static final String[] OUTPUT_LIMIT_ALIASES = {
        "max_output_tokens", "max_completion_tokens", "max_tokens"
    };

    public void requireWithinLimits(CanonicalRequest request, JsonNode capabilities) {
        var contextLimit = positive(capabilities, "max_context_tokens");
        var inputLimit = positive(capabilities, "max_input_tokens");
        var outputLimit = positive(capabilities, "max_output_tokens");
        var estimatedInput = estimateInputTokens(request.rawRequest());
        var requestedOutput = requestedOutput(request.rawRequest());

        if (inputLimit != null && estimatedInput > inputLimit) {
            throw OpenAiRequestException.invalid(inputParameter(request),
                "estimated input token count " + estimatedInput
                    + " exceeds the configured model input limit " + inputLimit);
        }
        if (outputLimit != null && requestedOutput.value() > outputLimit) {
            throw OpenAiRequestException.invalid(requestedOutput.parameter(),
                "requested output token count " + requestedOutput.value()
                    + " exceeds the configured model output limit " + outputLimit);
        }
        if (contextLimit != null
            && saturatedAdd(estimatedInput, requestedOutput.value()) > contextLimit) {
            throw OpenAiRequestException.invalid(inputParameter(request),
                "estimated input and requested output token budget "
                    + saturatedAdd(estimatedInput, requestedOutput.value())
                    + " exceeds the configured model context limit " + contextLimit);
        }
    }

    static long estimateInputTokens(JsonNode rawRequest) {
        if (rawRequest == null) return 1;
        var bytes = rawRequest.toString().getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (bytes + 2L) / 3L);
    }

    private RequestedOutput requestedOutput(JsonNode request) {
        if (request != null) {
            for (var alias : OUTPUT_LIMIT_ALIASES) {
                var value = request.path(alias);
                if (value.isIntegralNumber() && value.asLong() > 0) {
                    return new RequestedOutput(alias, value.asLong());
                }
            }
        }
        return new RequestedOutput("max_output_tokens", 0);
    }

    private Long positive(JsonNode capabilities, String field) {
        if (capabilities == null) return null;
        var value = capabilities.path(field);
        return value.isIntegralNumber() && value.asLong() > 0 ? value.asLong() : null;
    }

    private String inputParameter(CanonicalRequest request) {
        return request.protocol() == CanonicalRequest.Protocol.RESPONSES ? "input" : "messages";
    }

    private long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record RequestedOutput(String parameter, long value) {}
}
