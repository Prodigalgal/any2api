package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import com.any2api.account.AccountUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

class OpenAiResponseWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiResponseWriter writer = new OpenAiResponseWriter(mapper);

    @Test
    void rendersCompleteChatStreamingContract() {
        var request = request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, true);
        ((tools.jackson.databind.node.ObjectNode) request.rawRequest())
            .putObject("stream_options").put("include_usage", true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/chat/completions").build());

        writer.write(request, events(), exchange).block();
        var body = exchange.getResponse().getBodyAsString().block();

        assertThat(body).startsWith(": request_id=request-id\n\n")
            .contains("chat.completion.chunk")
            .contains("reasoning_content")
            .contains("tool_calls")
            .contains("prompt_tokens")
            .contains("\"usage_source\":\"UPSTREAM\"")
            .contains("data: [DONE]");
        assertThat(body.indexOf("\"finish_reason\":\"tool_calls\""))
            .isLessThan(body.indexOf("\"prompt_tokens\":4"));
        assertThat(body.indexOf("\"prompt_tokens\":4"))
            .isLessThan(body.indexOf("data: [DONE]"));
    }

    @Test
    void omitsChatStreamUsageUnlessTheClientRequestsIt() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/chat/completions").build());

        writer.write(request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, true), events(), exchange)
            .block();

        assertThat(exchange.getResponse().getBodyAsString().block())
            .doesNotContain("prompt_tokens");
    }

    @Test
    void emitsZeroChatUsageWhenRequestedAndTheProviderHasNoCounters() {
        var request = request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, true);
        ((tools.jackson.databind.node.ObjectNode) request.rawRequest())
            .putObject("stream_options").put("include_usage", true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/chat/completions").build());
        var eventsWithoutUsage = events().filter(
            event -> !(event instanceof CanonicalEvent.Usage));

        writer.write(request, eventsWithoutUsage, exchange).block();

        var body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"choices\":[]")
            .contains("\"prompt_tokens\":0")
            .contains("\"completion_tokens\":0");
        assertThat(body.indexOf("\"finish_reason\":\"tool_calls\""))
            .isLessThan(body.indexOf("\"prompt_tokens\":0"));
    }

    @Test
    void rendersCompleteResponsesStreamingContract() {
        var request = request(CanonicalRequest.Protocol.RESPONSES, true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/responses").build());

        writer.write(request, events(), exchange).block();
        var body = exchange.getResponse().getBodyAsString().block();

        assertThat(body).startsWith(": request_id=request-id\n\n")
            .contains("response.created")
            .contains("response.reasoning_summary_text.done")
            .contains("response.output_text.done")
            .contains("response.function_call_arguments.done")
            .contains("response.output_item.done")
            .contains("response.completed")
            .contains("\"output\":[")
            .contains("\"total_tokens\":7");
    }

    @Test
    void rendersCollectedChatAndResponsesFromSameCanonicalEvents() {
        var chat = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/chat/completions").build());
        var responses = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/responses").build());

        writer.write(request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, false), events(), chat).block();
        writer.write(request(CanonicalRequest.Protocol.RESPONSES, false), events(), responses).block();

        assertThat(chat.getResponse().getBodyAsString().block())
            .contains("\"object\":\"chat.completion\"")
            .contains("\"reasoning_content\":\"why\"")
            .contains("\"tool_calls\"");
        assertThat(responses.getResponse().getBodyAsString().block())
            .contains("\"object\":\"response\"")
            .contains("\"type\":\"reasoning\"")
            .contains("\"type\":\"function_call\"")
            .contains("\"type\":\"message\"");
    }

    @Test
    void normalizesPreExecutionAccountFailuresForBothProtocols() {
        var chat = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/chat/completions").build());
        var responses = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/responses").build());
        var failure = Flux.<CanonicalEvent>error(new AccountUnavailableException("alpha"));

        writer.write(request(CanonicalRequest.Protocol.CHAT_COMPLETIONS, false), failure, chat)
            .block();
        writer.write(request(CanonicalRequest.Protocol.RESPONSES, false), failure, responses)
            .block();

        assertThat(chat.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(responses.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(chat.getResponse().getBodyAsString().block())
            .contains("account_unavailable")
            .contains("no eligible account for provider alpha");
        assertThat(responses.getResponse().getBodyAsString().block())
            .contains("account_unavailable")
            .contains("no eligible account for provider alpha");
    }

    @Test
    void mapsProviderRateLimitsToOpenAiCompatibleHttpStatus() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/alpha/v1/responses").build());
        Flux<CanonicalEvent> failure = Flux.just(new CanonicalEvent.Failed(
            1, "request-id", 0, "rate_limited", "retry later", Map.of()));

        writer.write(request(CanonicalRequest.Protocol.RESPONSES, false), failure, exchange)
            .block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getBodyAsString().block())
            .contains("\"code\":\"rate_limited\"");
    }

    private CanonicalRequest request(CanonicalRequest.Protocol protocol, boolean stream) {
        var raw = mapper.createObjectNode().put("model", "alpha/model").put("stream", stream);
        raw.putArray("tools").add(mapper.createObjectNode().put("type", "function"));
        return new CanonicalRequest("request-id", protocol, "alpha", "model", stream,
            List.of(), Map.of(), Map.of(), List.of(), Map.of(), raw);
    }

    private Flux<CanonicalEvent> events() {
        return Flux.just(
            new CanonicalEvent.ResponseStarted(1, "request-id", 0, "resp_test"),
            new CanonicalEvent.ReasoningDelta(1, "request-id", 1, "why"),
            new CanonicalEvent.OutputTextDelta(1, "request-id", 2, "answer"),
            new CanonicalEvent.ToolCallStarted(1, "request-id", 3, "call_1", "lookup"),
            new CanonicalEvent.ToolArgumentsDelta(1, "request-id", 4, "call_1", "{\"id\":1}"),
            new CanonicalEvent.ToolCallCompleted(1, "request-id", 5, "call_1", "{\"id\":1}"),
            new CanonicalEvent.Usage(1, "request-id", 6, 4, 3, 0),
            new CanonicalEvent.Completed(1, "request-id", 7, "tool_calls"));
    }
}
