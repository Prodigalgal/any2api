package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.config.Any2ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class LifecycleAutomationClientTest {

    @Test
    void acceptsBoundedBrowserStateResponseAboveDefaultCodecLimit() {
        var responseBody = "{\"ok\":true,\"padding\":\""
            + "x".repeat(512 << 10)
            + "\"}";
        var properties = new Any2ApiProperties();
        var mapper = new ObjectMapper();
        var strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(
                properties.getAutomation().getMaxResponseBytes()))
            .build();
        ExchangeFunction exchange = ignored -> Mono.just(ClientResponse.create(HttpStatus.OK, strategies)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(responseBody)
            .build());
        var client = new LifecycleAutomationClient(
            WebClient.builder().exchangeStrategies(strategies).exchangeFunction(exchange),
            properties, mapper);

        var response = client.execute(
            "arena", "reauthenticate", mapper.createObjectNode()).block();

        assertThat(response).isNotNull();
        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("padding").asText()).hasSize(512 << 10);
    }

    @Test
    void rejectsConfiguredAutomationResponseLimitOutsideSafeBounds() {
        var properties = new Any2ApiProperties();

        assertThatThrownBy(
            () -> properties.getAutomation().setMaxResponseBytes(128 << 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
            () -> properties.getAutomation().setMaxResponseBytes(64 << 20))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
