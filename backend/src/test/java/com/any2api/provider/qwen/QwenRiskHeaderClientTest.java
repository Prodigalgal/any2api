package com.any2api.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.config.Any2ApiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.node.JsonNodeFactory;

class QwenRiskHeaderClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void browserFetchAcceptsTheBoundedLargeCredentialPatchReturnedByAutomation() throws Exception {
        var padding = "x".repeat(300_000);
        var response = ("""
            {"status":200,"content_type":"application/json","body_base64":"e30=",
            "credential_patch":{"padding":"%s"},"transport_mode":"native_browser_buffered"}
            """).formatted(padding).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/providers/qwen/browser-fetch", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var properties = new Any2ApiProperties();
        properties.getAutomation().setBaseUrl(URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort()));
        var client = new QwenRiskHeaderClient(WebClient.builder(), properties);

        var result = client.browserFetch(
            "POST", "/api/v2/models/", "", "token-value-that-is-long-enough",
            "account-id", Map.of(), JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(), "", "/", 30)
            .block(Duration.ofSeconds(10));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo("{}");
        assertThat(result.credentialPatch().path("padding").asText()).hasSize(300_000);
    }
}
