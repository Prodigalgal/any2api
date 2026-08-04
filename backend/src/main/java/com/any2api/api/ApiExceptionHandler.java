package com.any2api.api;

import com.any2api.coordination.AccountCapacityException;
import com.any2api.media.MediaCoordinator;
import com.any2api.auth.ApiKeyScopeException;
import com.any2api.protocol.OpenAiRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiKeyScopeException.class)
    public ResponseEntity<Map<String, Object>> apiKeyScope(ApiKeyScopeException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", Map.of(
            "type", "insufficient_scope",
            "message", error.getMessage(),
            "code", "insufficient_scope")));
    }

    @ExceptionHandler(OpenAiRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> openAiRequest(OpenAiRequestException error) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("type", error.type());
        detail.put("message", error.getMessage());
        detail.put("param", error.parameter());
        detail.put("code", error.type());
        if (!error.acceptedParameters().isEmpty()) {
            detail.put("accepted_parameters", error.acceptedParameters());
        }
        return Map.of("error", detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException error) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("type", "invalid_request_error");
        detail.put("message", error.getMessage());
        detail.put("param", null);
        detail.put("code", "invalid_request_error");
        return Map.of("error", detail);
    }

    @ExceptionHandler(AccountCapacityException.class)
    public ResponseEntity<Map<String, Object>> accountBusy(AccountCapacityException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", Map.of(
            "type", "account_busy",
            "message", "account is currently at its concurrency limit",
            "code", "account_busy")));
    }

    @ExceptionHandler(MediaCoordinator.MediaProviderException.class)
    public ResponseEntity<Map<String, Object>> mediaProvider(
        MediaCoordinator.MediaProviderException error
    ) {
        var failure = error.failure();
        var status = "rate_limited".equals(failure.type())
            ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
            "type", failure.type(),
            "message", failure.message())));
    }
}
