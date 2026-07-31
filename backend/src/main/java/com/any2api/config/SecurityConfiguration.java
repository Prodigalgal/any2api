package com.any2api.config;

import com.any2api.auth.AdminSessionWebFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;

@Configuration
public class SecurityConfiguration {

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
