package com.any2api.api;

import com.any2api.coordination.AccountCapacityException;
import com.any2api.media.MediaCoordinator;
import com.any2api.auth.ApiKeyScopeException;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.observability.RequestIdWebFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiKeyScopeException.class)
    public ResponseEntity<Map<String, Object>> apiKeyScope(
        ApiKeyScopeException error,
        ServerWebExchange exchange
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response(
            "insufficient_scope", "insufficient_scope", error.getMessage(), null,
            false, exchange, Map.of()));
    }

    @ExceptionHandler(OpenAiRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> openAiRequest(
        OpenAiRequestException error,
        ServerWebExchange exchange
    ) {
        var extra = error.acceptedParameters().isEmpty() ? Map.<String, Object>of()
            : Map.<String, Object>of("accepted_parameters", error.acceptedParameters());
        return response(error.type(), error.type(), error.getMessage(), error.parameter(),
            false, exchange, extra);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(
        IllegalArgumentException error,
        ServerWebExchange exchange
    ) {
        return response("invalid_request_error", "invalid_request_error", error.getMessage(),
            null, false, exchange, Map.of());
    }

    @ExceptionHandler(AccountCapacityException.class)
    public ResponseEntity<Map<String, Object>> accountBusy(
        AccountCapacityException error,
        ServerWebExchange exchange
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response(
            "account_busy", "account_busy",
            "account is currently at its concurrency limit", null, true, exchange, Map.of()));
    }

    @ExceptionHandler(MediaCoordinator.MediaProviderException.class)
    public ResponseEntity<Map<String, Object>> mediaProvider(
        MediaCoordinator.MediaProviderException error,
        ServerWebExchange exchange
    ) {
        var failure = error.failure();
        var status = "rate_limited".equals(failure.type())
            ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(response(
            failure.type(), failure.type(), failure.message(), null, failure.retryable(),
            exchange, Map.of()));
    }

    private Map<String, Object> response(
        String type,
        String code,
        String message,
        String param,
        boolean retryable,
        ServerWebExchange exchange,
        Map<String, Object> extra
    ) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("type", type);
        detail.put("code", code);
        detail.put("message", message);
        detail.put("param", param);
        detail.put("retryable", retryable);
        detail.put("provider", exchange.getAttribute(RequestIdWebFilter.PROVIDER_ATTRIBUTE));
        detail.put("model", exchange.getAttribute(RequestIdWebFilter.MODEL_ATTRIBUTE));
        detail.put("request_id", RequestIdWebFilter.get(exchange));
        detail.putAll(extra);
        return Map.of("error", detail);
    }
}
