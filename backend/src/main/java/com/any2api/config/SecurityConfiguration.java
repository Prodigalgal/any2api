package com.any2api.config;

import com.any2api.auth.AdminSessionWebFilter;
import com.any2api.auth.ApiKeyRateLimiter;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.firewall.ServerWebExchangeFirewall;
import org.springframework.security.web.server.firewall.StrictServerWebExchangeFirewall;

@Configuration
public class SecurityConfiguration {
    private static final Pattern MODEL_DETAIL_PATH = Pattern.compile(
        "^/(?:[a-z][a-z0-9_-]{1,31}/)?v1/models/.+$");

    @Bean
    ServerWebExchangeFirewall modelIdServerWebExchangeFirewall() {
        var defaultFirewall = new StrictServerWebExchangeFirewall();
        var modelIdFirewall = new StrictServerWebExchangeFirewall();
        modelIdFirewall.setAllowUrlEncodedSlash(true);
        // Only opaque model IDs may contain encoded slashes; authentication route prefixes stay literal.
        return exchange -> (HttpMethod.GET.equals(exchange.getRequest().getMethod())
            && MODEL_DETAIL_PATH.matcher(exchange.getRequest().getURI().getRawPath()).matches()
                ? modelIdFirewall : defaultFirewall).getFirewalledExchange(exchange);
    }

    @Bean
    ApiKeyRateLimiter apiKeyRateLimiter(Any2ApiProperties properties) {
        return new ApiKeyRateLimiter(properties);
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
        ServerHttpSecurity http,
        AdminSessionWebFilter adminSessionWebFilter
    ) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers(HttpMethod.POST, "/api/admin/v1/session").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/admin/v1/session").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/admin/v1/login-challenge").permitAll()
                .pathMatchers("/healthz", "/readyz", "/actuator/health/**").permitAll()
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .anyExchange().permitAll())
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                (exchange, ignored) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }))
            .addFilterAt(adminSessionWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
    }
}
