package com.any2api.api.admin;

import com.any2api.auth.AdminSessionService;
import com.any2api.auth.LoginChallengeService;
import com.any2api.config.Any2ApiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminSessionController {
    private final AdminSessionService sessions;
    private final Any2ApiProperties properties;
    private final LoginChallengeService challenges;

    public AdminSessionController(
        AdminSessionService sessions,
        Any2ApiProperties properties,
        LoginChallengeService challenges
    ) {
        this.sessions = sessions;
        this.properties = properties;
        this.challenges = challenges;
    }

    @GetMapping("/login-challenge")
    public Mono<LoginChallengeService.Challenge> challenge() {
        return challenges.issue();
    }

    @PostMapping("/session")
    public Mono<Map<String, Object>> login(
        @RequestBody LoginRequest request,
        ServerWebExchange exchange
    ) {
        return challenges.verify(request.challengeToken(), request.mathAnswer(), request.powNonce())
            .flatMap(valid -> {
                var session = valid
                    ? sessions.authenticate(request.username(), request.password()).orElse(null)
                    : null;
                if (session == null) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "login verification failed"));
                }
                exchange.getResponse().addCookie(cookie(session.token(),
                    Duration.between(Instant.now(), session.expiresAt())));
                return Mono.just(Map.<String, Object>of(
                    "authenticated", true,
                    "username", session.username(),
                    "expiresAt", session.expiresAt()));
            });
    }

    @GetMapping("/session")
    public Mono<Map<String, Object>> current() {
        return org.springframework.security.core.context.ReactiveSecurityContextHolder.getContext()
            .filter(context -> context.getAuthentication() != null
                && context.getAuthentication().getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())))
            .map(context -> Map.<String, Object>of("authenticated", true,
                "username", context.getAuthentication().getName()))
            .defaultIfEmpty(Map.<String, Object>of("authenticated", false));
    }

    @DeleteMapping("/session")
    public Map<String, Object> logout(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(cookie("", Duration.ZERO));
        return Map.of("authenticated", false);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminSessionService.COOKIE_NAME, value)
            .httpOnly(true)
            .secure(properties.getSecurity().isAdminSessionSecure())
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    public record LoginRequest(
        String username,
        String password,
        String challengeToken,
        String mathAnswer,
        long powNonce
    ) {}
}
