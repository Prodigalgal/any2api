package com.any2api.api;

import com.any2api.media.MediaCoordinator;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException error) {
        return Map.of("error", Map.of(
            "type", "invalid_request_error",
            "message", error.getMessage()));
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
