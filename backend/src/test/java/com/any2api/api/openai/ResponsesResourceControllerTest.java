package com.any2api.api.openai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.api.ApiExceptionHandler;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.protocol.state.ResponsesService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class ResponsesResourceControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesService service = mock(ResponsesService.class);
    private final ApiKeyAuthorization authorization = mock(ApiKeyAuthorization.class);
    private final ApiKeyGrant grant = ApiKeyGrant.unrestricted();

    @Test void randomResourcePathsUseOwnedStateAndCannotBeCached() {
        when(authorization.grant(any())).thenReturn(grant);
        when(service.retrieve("owned", null, grant)).thenReturn(Mono.just(mapper.createObjectNode().put("id", "owned")));
        when(service.delete("owned", null, grant)).thenReturn(Mono.just(mapper.createObjectNode().put("deleted", true)));
        when(service.listInput("owned", null, grant, 20, "desc", null, null))
            .thenReturn(Mono.just(mapper.createObjectNode().put("object", "list").set("data", mapper.createArrayNode())));
        var client = WebTestClient.bindToController(new ResponsesResourceController(service, authorization))
            .controllerAdvice(new ApiExceptionHandler()).build();
        for (var prefix : java.util.List.of("/random/v1", "/multimodal-random/v1")) {
            client.get().uri(prefix + "/responses/owned").exchange().expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore()).expectBody().jsonPath("$.id").isEqualTo("owned");
            client.get().uri(prefix + "/responses/owned/input_items").exchange().expectStatus().isOk().expectBody().jsonPath("$.object").isEqualTo("list");
            client.delete().uri(prefix + "/responses/owned").exchange().expectStatus().isOk().expectBody().jsonPath("$.deleted").isEqualTo(true);
        }
        client.get().uri("/v1/responses/owned?stream=true").exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.error.param").isEqualTo("stream");
    }

    @Test void providerResourcesPassTheirProviderHintToTheOwnershipCheck() {
        when(authorization.grant(any())).thenReturn(grant);
        when(service.retrieve("owned", "mimo", grant)).thenReturn(Mono.just(mapper.createObjectNode().put("id", "owned")));
        WebTestClient.bindToController(new ResponsesResourceController(service, authorization)).build()
            .get().uri("/mimo/v1/responses/owned").exchange().expectStatus().isOk();
        verify(service).retrieve(eq("owned"), eq("mimo"), eq(grant));
    }
}
