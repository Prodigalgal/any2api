package com.any2api.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyProtocol;
import com.any2api.auth.ApiKeyProviderScope;
import com.any2api.auth.ApiKeyRequestFeatureDetector;
import com.any2api.observability.RequestIdWebFilter;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.protocol.state.ResponsesService;
import com.any2api.provider.InferenceCoordinator;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.routing.ProviderRouteResolver;
import com.any2api.routing.RandomInferenceRouter;
import com.any2api.routing.ResolvedRoute;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class OpenAiGatewayControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalRequestParser parser = new CanonicalRequestParser(mapper);
    private final InferenceCoordinator coordinator = mock(InferenceCoordinator.class);
    private final RandomInferenceRouter random = mock(RandomInferenceRouter.class);
    private final ResponsesService responses = mock(ResponsesService.class);
    private final ApiKeyGrant grant = new ApiKeyGrant(UUID.randomUUID(), "client",
        Map.of("alpha", ApiKeyProviderScope.allModels("alpha"), "beta", ApiKeyProviderScope.allModels("beta")),
        Set.of(ApiKeyProtocol.CHAT_COMPLETIONS, ApiKeyProtocol.RESPONSES), Set.of(), null, false, ProviderTransportMode.API);
    private final OpenAiGatewayController controller = new OpenAiGatewayController(mock(ProviderRouteResolver.class),
        parser, coordinator, new OpenAiResponseWriter(mapper), random, new ApiKeyAuthorization(), new ApiKeyRequestFeatureDetector(), responses);

    @Test void cascadesBeforeStreamingAndRetainsTheSubscribedSuccessfulSource() {
        var raw = mapper.createObjectNode().put("model", "random").put("stream", true);
        raw.putArray("messages").addObject().put("role", "user").put("content", "hello");
        var first = selection("alpha", raw);
        var next = selection("beta", raw);
        when(random.selectCandidates(any(), eq(raw), any(), eq(grant), eq("random-test"))).thenReturn(Flux.just(first, next));
        when(coordinator.execute(eq(first.request()), eq(first.account()), eq(grant.keyId()), eq(ProviderTransportMode.AUTO)))
            .thenReturn(Flux.just(new CanonicalEvent.Failed(1, "random-test", 0, "rate_limited", "busy", Map.of())));
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        when(coordinator.execute(eq(next.request()), eq(next.account()), eq(grant.keyId()), eq(ProviderTransportMode.AUTO)))
            .thenReturn(Flux.<CanonicalEvent>just(new CanonicalEvent.ResponseStarted(1, "random-test", 0, "resp_success"),
                new CanonicalEvent.OutputTextDelta(1, "random-test", 1, "retained stream"),
                new CanonicalEvent.Completed(1, "random-test", 2, "stop"))
                .delayElements(Duration.ofMillis(5)).doOnSubscribe(ignored -> subscriptions.incrementAndGet()));
        var exchange = exchange("/random/v1/chat/completions");
        controller.randomChat(raw, exchange).block(Duration.ofSeconds(3));
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Any2API-Provider")).isEqualTo("beta");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("retained stream", "[DONE]");
        assertThat(subscriptions.get()).isEqualTo(1);
    }

    @Test void doesNotChangeProviderAfterTheResponseHasStarted() {
        var raw = mapper.createObjectNode().put("model", "random").put("stream", true);
        var first = selection("alpha", raw);
        var next = selection("beta", raw);
        when(random.selectCandidates(any(), eq(raw), any(), eq(grant), any())).thenReturn(Flux.just(first, next));
        when(coordinator.execute(eq(first.request()), eq(first.account()), eq(grant.keyId()), any()))
            .thenReturn(Flux.just(new CanonicalEvent.ResponseStarted(1, "random-test", 0, "resp_partial"),
                new CanonicalEvent.OutputTextDelta(1, "random-test", 1, "partial"),
                new CanonicalEvent.Failed(1, "random-test", 2, "upstream_unavailable", "failed", Map.of())));
        var exchange = exchange("/random/v1/chat/completions");
        controller.randomChat(raw, exchange).block();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Any2API-Provider")).isEqualTo("alpha");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("partial", "upstream_unavailable");
        verify(coordinator, never()).execute(eq(next.request()), eq(next.account()), any(), any());
    }

    @Test void randomStoredContinuationUsesItsOriginalRouteAndExistingAutoPolicy() {
        var raw = mapper.createObjectNode().put("model", "random").put("previous_response_id", "resp_gw_owned").put("input", "continue");
        var route = new ResolvedRoute("alpha", "fixture");
        var request = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw, "random-test");
        when(responses.storedContinuationRoute(raw, grant)).thenReturn(Mono.just(route));
        when(responses.prepare(raw, route, grant, "random-test")).thenReturn(Mono.just(new ResponsesService.Prepared(request, request)));
        when(responses.track(eq(request), eq(grant), any())).thenAnswer(call -> call.getArgument(2));
        when(coordinator.execute(request, grant.keyId(), ProviderTransportMode.AUTO)).thenReturn(Flux.just(
            new CanonicalEvent.ResponseStarted(1, "random-test", 0, "resp_follow"),
            new CanonicalEvent.OutputTextDelta(1, "random-test", 1, "continued"), new CanonicalEvent.Completed(1, "random-test", 2, "stop")));
        var exchange = exchange("/random/v1/responses");
        controller.randomResponses(raw, exchange).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("continued");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Any2API-Provider")).isEqualTo("alpha");
        verify(random, never()).selectCandidates(any(), any(), any(), any(), any());
    }

    private RandomInferenceRouter.RandomSelection selection(String provider, tools.jackson.databind.node.ObjectNode raw) {
        var request = parser.parse(CanonicalRequest.Protocol.CHAT_COMPLETIONS, new ResolvedRoute(provider, "fixture"), raw, "random-test");
        return new RandomInferenceRouter.RandomSelection(request, mock(LeasedProviderAccount.class));
    }

    private MockServerWebExchange exchange(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
        exchange.getAttributes().put(ApiKeyAuthorization.GRANT_ATTRIBUTE, grant);
        exchange.getAttributes().put(RequestIdWebFilter.ATTRIBUTE, "random-test");
        return exchange;
    }
}
