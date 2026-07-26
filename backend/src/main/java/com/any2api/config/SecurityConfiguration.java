package com.any2api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .anyExchange().permitAll())
            .httpBasic(basic -> {})
            .build();
    }

    @Bean
    MapReactiveUserDetailsService adminUsers(Any2ApiProperties properties) {
        var security = properties.getSecurity();
        var password = security.getAdminPassword().isBlank() ? "local-admin" : security.getAdminPassword();
        var user = User.withUsername(security.getAdminUsername())
            .password("{noop}" + password)
            .roles("ADMIN")
            .build();
        return new MapReactiveUserDetailsService(user);
    }
}

