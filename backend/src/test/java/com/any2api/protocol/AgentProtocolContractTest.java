package com.any2api.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.routing.ResolvedRoute;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentProtocolContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalRequestParser parser = new CanonicalRequestParser(mapper);
    private final OpenAiResponseWriter writer = new OpenAiResponseWriter(mapper);

    @Test
    void replaysReasoningPhaseParallelCallsAndMultimodalResults() {
        var raw = raw("""
            {"input":[{"type":"reasoning","summary":[{"type":"summary_text","text":"plan"}]},
             {"type":"message","role":"assistant","phase":"commentary","content":"checking"},
             {"type":"function_call","call_id":"one","name":"inspect","arguments":"{}"},
             {"type":"function_call","call_id":"two","name":"inspect","arguments":"{}"},
             {"type":"function_call_output","call_id":"one","output":[{"type":"input_text","text":"result"}]}]}
            """);
        var request = parse(raw);
        assertThat(request.messages()).hasSize(3);
        assertThat(request.messages().get(0).path("phase").asText()).isEqualTo("commentary");
        assertThat(request.messages().get(1).path("tool_calls").size()).isEqualTo(2);
        assertThat(request.messages().get(2).path("content").isArray()).isTrue();
        assertThat(request.rawRequest().path("input").get(0).path("type").asText()).isEqualTo("reasoning");
    }

    @Test
    void restoresNamespacedCustomToolAndAcceptsItsReturnedInput() {
        var request = parse(raw("""
            {"stream":true,"tools":[{"type":"namespace","name":"local","tools":[{"type":"custom","name":"run"}]}]}
            """));
        var name = request.tools().getFirst().path("name").asText();
        var events = List.<CanonicalEvent>of(
            new CanonicalEvent.ResponseStarted(1,"agent-test",0,"resp_test"),
            new CanonicalEvent.ToolCallStarted(1,"agent-test",1,"call_one",name),
            new CanonicalEvent.ToolCallCompleted(1,"agent-test",2,"call_one","{\"input\":\"hello\\nworld\"}"),
            new CanonicalEvent.Completed(1,"agent-test",3,"tool_calls"));
        var exchange = exchange();
        writer.write(request, Flux.fromIterable(events), exchange).block();
        var frames = frames(exchange);
        assertThat(frames.stream().map(frame -> frame.path("type").asText())).contains("response.custom_tool_call_input.done");
        var response = frames.getLast().path("response");
        var call = response.path("output").get(0);
        assertThat(call.path("type").asText()).isEqualTo("custom_tool_call");
        assertThat(call.path("namespace").asText()).isEqualTo("local");
        assertThat(call.path("name").asText()).isEqualTo("run");
        assertThat(call.path("input").asText()).isEqualTo("hello\nworld");
        var followUp = (ObjectNode) request.rawRequest().deepCopy();
        followUp.putArray("input").add(call).addObject().put("type","custom_tool_call_output")
            .put("call_id","call_one").put("output","done");
        assertThat(parse(followUp).messages().getFirst().path("tool_calls").get(0).path("function").path("name").asText())
            .isEqualTo(name);
    }

    @Test
    void preservesIndexesAndCreationTimeInFinalOutput() {
        var request = parse(raw("{\"stream\":true}"));
        var exchange = exchange();
        writer.write(request, Flux.just(
            new CanonicalEvent.ResponseStarted(1,"agent-test",0,"resp_test"),
            new CanonicalEvent.ToolCallStarted(1,"agent-test",1,"one","inspect"),
            new CanonicalEvent.ToolCallCompleted(1,"agent-test",2,"one","{}"),
            new CanonicalEvent.OutputTextDelta(1,"agent-test",3,"text"),
            new CanonicalEvent.ReasoningDelta(1,"agent-test",4,"summary"),
            new CanonicalEvent.Completed(1,"agent-test",5,"tool_calls")), exchange).block();
        var frames = frames(exchange);
        var response = frames.getLast().path("response");
        for (var frame : frames) if ("response.output_item.added".equals(frame.path("type").asText())) {
            var index = frame.path("output_index").asInt();
            assertThat(response.path("output").get(index).path("id")).isEqualTo(frame.path("item").path("id"));
        }
        assertThat(response.path("created_at")).isEqualTo(frames.getFirst().path("response").path("created_at"));
    }

    @Test
    void emitsIncompleteAndPreservesPartialFailure() {
        var request = parse(raw("{\"stream\":true}"));
        var exchange = exchange();
        writer.write(request, Flux.just(new CanonicalEvent.ResponseStarted(1,"agent-test",0,"resp_test"),
            new CanonicalEvent.OutputTextDelta(1,"agent-test",1,"partial"),
            new CanonicalEvent.Completed(1,"agent-test",2,"length")), exchange).block();
        var terminal = frames(exchange).getLast();
        assertThat(terminal.path("type").asText()).isEqualTo("response.incomplete");
        assertThat(terminal.path("response").path("incomplete_details").path("reason").asText()).isEqualTo("max_output_tokens");
        assertThat(terminal.path("response").path("output").get(0).path("status").asText()).isEqualTo("incomplete");
        var failureExchange = exchange();
        writer.write(request, Flux.just(new CanonicalEvent.ResponseStarted(1,"agent-test",0,"resp_test"),
            new CanonicalEvent.OutputTextDelta(1,"agent-test",1,"partial"),
            new CanonicalEvent.Failed(1,"agent-test",2,"upstream_unavailable","failed",java.util.Map.of())), failureExchange).block();
        assertThat(frames(failureExchange).getLast().path("response").path("output").get(0).path("content").get(0).path("text").asText())
            .isEqualTo("partial");
    }

    @Test
    void replaysRefusalAsTextWhilePreservingThePublicItem() {
        var request = parse(raw("""
            {"input":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"cannot do that"}]}]}
            """));
        assertThat(request.messages().getFirst().path("content").get(0).path("text").asText()).isEqualTo("cannot do that");
        assertThat(request.rawRequest().path("input").get(0).path("content").get(0).path("type").asText()).isEqualTo("refusal");
    }

    @Test
    void filtersAllowedToolsAndKeepsQualifiedNamesDistinct() {
        var request = parse(raw("""
            {"tools":[{"type":"namespace","name":"first","tools":[{"type":"function","name":"inspect"}]},
              {"type":"namespace","name":"second","tools":[{"type":"function","name":"inspect"}]}],
             "tool_choice":{"type":"allowed_tools","mode":"required","tools":[{"type":"function","namespace":"second","name":"inspect"}]}}
            """));
        assertThat(request.tools().get(0).path("name")).isNotEqualTo(request.tools().get(1).path("name"));
        var providerRequest = OpenAiToolBridge.forProvider(request);
        assertThat(providerRequest.tools()).hasSize(1);
        assertThat(providerRequest.tools().getFirst()).isEqualTo(request.tools().get(1));
        assertThat(providerRequest.generation()).containsEntry("tool_choice", "required");
        assertThat(request.rawRequest().path("tool_choice").path("type").asText()).isEqualTo("allowed_tools");
        assertThatThrownBy(() -> parse(raw("{\"tools\":[{\"type\":\"custom\",\"name\":\"run\",\"format\":{\"type\":\"grammar\"}}]}")))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining("grammar");
        assertThatThrownBy(() -> parse(raw("{\"tools\":[{\"type\":\"function\",\"name\":\"run\",\"strict\":\"true\"}]}")))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining("boolean");
        assertThatThrownBy(() -> OpenAiToolBridge.customInput("{\"input\":42}", mapper))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining("string input");
    }

    @Test
    void returnsHttpErrorBeforeOpeningStream() {
        var exchange = exchange();
        writer.write(parse(raw("{\"stream\":true}")), Flux.error(OpenAiRequestException.invalid("input","bad input")), exchange).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(400);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/json");
    }

    @Test
    void contextGuardKeepsDeveloperAndDoesNotAddChatFieldsToResponses() {
        var raw = raw("{}");
        var input = raw.putArray("input");
        input.addObject().put("role","developer").put("content","keep rule");
        for (var i=0;i<45;i++) input.addObject().put("role",i%2==0?"user":"assistant").put("content","turn "+i);
        var guarded = new SmartContextWindowManager(mapper).guard(parse(raw), null);
        assertThat(guarded.messages()).hasSize(46);
        assertThat(guarded.messages().getFirst().path("role").asText()).isEqualTo("developer");
        assertThat(guarded.rawRequest().has("messages")).isFalse();
        raw.put("truncation","disabled");
        assertThatThrownBy(() -> new SmartContextWindowManager(mapper).guard(parse(raw),
            mapper.createObjectNode().put("max_context_messages",32)))
            .isInstanceOf(OpenAiRequestException.class).hasMessageContaining("truncation disabled");
    }

    @Test
    void chatStreamsArgumentsAvailableOnlyAtToolCompletion() {
        var request = parser.parse(CanonicalRequest.Protocol.CHAT_COMPLETIONS,new ResolvedRoute("mimo","fixture"),raw("{\"stream\":true}"),"agent-test");
        var exchange = exchange();
        writer.write(request,Flux.just(new CanonicalEvent.ResponseStarted(1,"agent-test",0,"resp_test"),
            new CanonicalEvent.ToolCallStarted(1,"agent-test",1,"one","inspect"),
            new CanonicalEvent.ToolCallCompleted(1,"agent-test",2,"one","{\"path\":\".\"}"),
            new CanonicalEvent.Completed(1,"agent-test",3,"stop")),exchange).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\\\"path\\\"").contains("\"finish_reason\":\"tool_calls\"");
    }

    @Test
    void completesPartialToolArgumentsConsistentlyForBothProtocols() {
        var events = Flux.<CanonicalEvent>just(new CanonicalEvent.ResponseStarted(1, "agent-test", 0, "resp_test"),
            new CanonicalEvent.ToolCallStarted(1, "agent-test", 1, "one", "inspect"),
            new CanonicalEvent.ToolArgumentsDelta(1, "agent-test", 2, "one", "{\"path\":"),
            new CanonicalEvent.ToolCallCompleted(1, "agent-test", 3, "one", "{\"path\":\".\"}"),
            new CanonicalEvent.Completed(1, "agent-test", 4, "tool_calls"));
        for (var protocol : CanonicalRequest.Protocol.values()) {
            var request = parser.parse(protocol, new ResolvedRoute("mimo", "fixture"), raw("{\"stream\":true}"), "agent-test");
            var exchange = exchange();
            writer.write(request, events, exchange).block();
            var arguments = new StringBuilder();
            for (var frame : frames(exchange)) {
                if (protocol == CanonicalRequest.Protocol.RESPONSES && "response.function_call_arguments.delta".equals(frame.path("type").asText())) {
                    arguments.append(frame.path("delta").asText());
                } else if (protocol == CanonicalRequest.Protocol.CHAT_COMPLETIONS) {
                    arguments.append(frame.path("choices").path(0).path("delta").path("tool_calls").path(0).path("function").path("arguments").asText(""));
                }
            }
            assertThat(arguments.toString()).isEqualTo("{\"path\":\".\"}");
        }
    }

    private CanonicalRequest parse(ObjectNode raw) {
        return parser.parse(CanonicalRequest.Protocol.RESPONSES,new ResolvedRoute("mimo","fixture"),raw,"agent-test");
    }

    private ObjectNode raw(String json) { return (ObjectNode) mapper.readTree(json); }
    private MockServerWebExchange exchange() { return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses").build()); }
    private List<JsonNode> frames(MockServerWebExchange exchange) {
        var frames = new ArrayList<JsonNode>();
        for (var line : exchange.getResponse().getBodyAsString().block().split("\n")) {
            if (line.startsWith("data: ") && !line.equals("data: [DONE]")) frames.add(mapper.readTree(line.substring(6)));
        }
        return frames;
    }
}
