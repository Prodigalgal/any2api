package com.any2api.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.runtime.ProviderRuntimeRuleService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class OfficialBrowserTransportClientTest {

    @Test
    void preservesInternalActionErrorStatusForRequestAndStream() {
        var properties = new Any2ApiProperties();
        var mapper = new ObjectMapper();
        var strategies = ExchangeStrategies.withDefaults();
        ExchangeFunction exchange = ignored -> Mono.just(ClientResponse.create(
                HttpStatus.BAD_REQUEST, strategies)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body("{\"detail\":\"unsupported media\"}")
            .build());
        var client = new OfficialBrowserTransportClient(
            WebClient.builder().exchangeStrategies(strategies).exchangeFunction(exchange),
            properties,
            mock(ProviderRuntimeRuleService.class),
            mapper);

        var response = client.request(
                "arena", "chat", mapper.createObjectNode(), mapper.createObjectNode(),
                Map.of(), "", Map.of(), ProviderTransportMode.API)
            .block();
        var frames = client.stream(
                "arena", "chat", mapper.createObjectNode(), mapper.createObjectNode(),
                Map.of(), "", Map.of(), ProviderTransportMode.API)
            .collectList()
            .block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("unsupported media");
        assertThat(frames).isNotNull().hasSize(2);
        assertThat(frames).extracting(frame -> frame.path("type").asText())
            .containsExactly("status", "error");
        assertThat(frames.get(0).path("status").asInt()).isEqualTo(400);
        assertThat(frames.get(1).path("data").asText()).contains("HTTP 400");
    }
}
