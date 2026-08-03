package com.any2api.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.media.GeneratedMedia;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.media.MediaAssetService;
import com.any2api.media.MediaCoordinator;
import com.any2api.media.MediaResult;
import com.any2api.routing.ProviderRouteResolver;
import com.any2api.routing.ResolvedRoute;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import com.any2api.config.Any2ApiProperties;

class OpenAiImagesControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void urlResponseStoresBytesAndReturnsGatewayOwnedAssetUrl() {
        var routes = mock(ProviderRouteResolver.class);
        var coordinator = mock(MediaCoordinator.class);
        var assets = mock(MediaAssetService.class);
        var accountId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        when(routes.resolve(any(), any())).thenReturn(
            new ResolvedRoute("grok_web", "grok-imagine-image"));
        when(coordinator.execute(any(), nullable(UUID.class))).thenReturn(Mono.just(
            new MediaCoordinator.ExecutionResult(accountId, new MediaResult(List.of(
                new GeneratedMedia("image/webp", new byte[] {1, 2}, "prompt"))))));
        when(assets.save(any(), any(), any())).thenReturn(Mono.just(assetId));
        var authorization = mock(ApiKeyAuthorization.class);
        when(authorization.grant(any())).thenReturn(ApiKeyGrant.unrestricted());
        var controller = new OpenAiImagesController(
            routes, coordinator, assets, mapper, new Any2ApiProperties(),
            authorization);
        var request = mapper.createObjectNode()
            .put("model", "grok_web/grok-imagine-image")
            .put("prompt", "prompt");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "https://api.example.test/v1/images/generations").build());

        var result = controller.generate(request, exchange).block();

        assertThat(result).isNotNull();
        assertThat((List<?>) result.get("data")).singleElement().satisfies(item -> {
            var value = ((java.util.Map<?, ?>) item).get("url");
            assertThat(value).isEqualTo(
                "https://api.example.test/v1/media/images/" + assetId);
        });
    }

    @Test
    void base64ResponseDoesNotPersistGeneratedBytes() {
        var routes = mock(ProviderRouteResolver.class);
        var coordinator = mock(MediaCoordinator.class);
        var assets = mock(MediaAssetService.class);
        when(routes.resolve(any(), any())).thenReturn(
            new ResolvedRoute("grok_web", "grok-imagine-image"));
        when(coordinator.execute(any(), nullable(UUID.class))).thenReturn(Mono.just(
            new MediaCoordinator.ExecutionResult(UUID.randomUUID(), new MediaResult(List.of(
                new GeneratedMedia("image/png", new byte[] {1, 2, 3}, ""))))));
        var authorization = mock(ApiKeyAuthorization.class);
        when(authorization.grant(any())).thenReturn(ApiKeyGrant.unrestricted());
        var controller = new OpenAiImagesController(
            routes, coordinator, assets, mapper, new Any2ApiProperties(),
            authorization);
        var request = mapper.createObjectNode()
            .put("model", "grok_web/grok-imagine-image")
            .put("prompt", "prompt")
            .put("response_format", "b64_json");
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/images/generations").build());

        var result = controller.generate(request, exchange).block();

        assertThat((List<?>) result.get("data")).singleElement().satisfies(item ->
            assertThat(((java.util.Map<?, ?>) item).get("b64_json")).isEqualTo("AQID"));
        org.mockito.Mockito.verifyNoInteractions(assets);
    }
}
