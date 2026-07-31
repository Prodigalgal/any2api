package com.any2api.auth;

import java.util.List;
import org.springframework.http.HttpCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AdminSessionWebFilter implements WebFilter {
    private final AdminSessionService sessions;

    public AdminSessionWebFilter(AdminSessionService sessions) { this.sessions = sessions; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpCookie cookie = exchange.getRequest().getCookies()
            .getFirst(AdminSessionService.COOKIE_NAME);
        var username = sessions.verify(cookie == null ? null : cookie.getValue());
        if (username.isEmpty()) return chain.filter(exchange);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            username.get(), cookie.getValue(), List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
