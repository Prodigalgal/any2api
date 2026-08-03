package com.any2api.auth;

import com.any2api.protocol.CanonicalRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class ApiKeyAuthorization {
    public static final String GRANT_ATTRIBUTE = ApiKeyAuthorization.class.getName() + ".grant";

    public ApiKeyGrant grant(ServerWebExchange exchange) {
        return current(exchange).orElseThrow(() ->
            new IllegalStateException("API key grant is unavailable for inference request"));
    }

    public Optional<ApiKeyGrant> current(ServerWebExchange exchange) {
        var grant = exchange.getAttribute(GRANT_ATTRIBUTE);
        return grant instanceof ApiKeyGrant value ? Optional.of(value) : Optional.empty();
    }

    public void require(
        ServerWebExchange exchange,
        ApiKeyProtocol protocol,
        String providerId,
        String model
    ) {
        require(grant(exchange), protocol, providerId, model);
    }

    public void require(
        ApiKeyGrant grant,
        ApiKeyProtocol protocol,
        String providerId,
        String model
    ) {
        if (!grant.allowsProtocol(protocol)) {
            throw new ApiKeyScopeException("API key does not allow this protocol");
        }
        if (!grant.allowsModel(providerId, model)) {
            throw new ApiKeyScopeException("API key does not allow the requested provider/model");
        }
    }

    public ApiKeyProtocol protocol(CanonicalRequest.Protocol protocol) {
        return protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS
            ? ApiKeyProtocol.CHAT_COMPLETIONS : ApiKeyProtocol.RESPONSES;
    }
}
