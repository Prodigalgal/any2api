package com.any2api.provider;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolEmulationEngineTest {
    private ObjectMapper mapper;
    private ToolEmulationEngine engine;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        engine = new ToolEmulationEngine(mapper);
    }

    @Test
    void plansValidToolsAndHandlesChoice() {
        var rawTools = mapper.createArrayNode();
        var tool1 = rawTools.addObject().put("type", "function").putObject("function");
        tool1.put("name", "get_weather").put("description", "Get weather information");
        var tool2 = rawTools.addObject().put("type", "function").putObject("function");
        tool2.put("name", "search_web").put("description", "Search the web");

        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var request = createRequest(rawTools, raw);

        var plan = engine.plan(request);
        assertThat(plan.enabled()).isTrue();
        assertThat(plan.required()).isTrue();
        assertThat(plan.parallel()).isTrue();
        assertThat(plan.tools()).hasSize(2);
        assertThat(plan.tools().get(0).name()).isEqualTo("get_weather");
        assertThat(plan.tools().get(1).name()).isEqualTo("search_web");
    }

    @Test
    void rejectsInvalidToolNamesAndDuplicateTools() {
        var rawTools = mapper.createArrayNode();
        var tool1 = rawTools.addObject().put("type", "function").putObject("function");
        tool1.put("name", "invalid tool name with spaces");

        var request = createRequest(rawTools, mapper.createObjectNode());
        assertThatThrownBy(() -> engine.plan(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("function tool name is invalid");

        var dupTools = mapper.createArrayNode();
        dupTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        dupTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        var dupRequest = createRequest(dupTools, mapper.createObjectNode());
        assertThatThrownBy(() -> engine.plan(dupRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate function tool");
    }

    @Test
    void formatsInstructionContractWithToolSchema() {
        var rawTools = mapper.createArrayNode();
        var tool1 = rawTools.addObject().put("type", "function").putObject("function");
        tool1.put("name", "calculator").put("description", "Perform calculations");
        var plan = engine.plan(createRequest(rawTools, mapper.createObjectNode()));

        var instruction = engine.appendContract("You are an assistant.", plan);
        assertThat(instruction).contains("You are an assistant.")
            .contains("[Tool calling contract]")
            .contains("calculator")
            .contains("tool_calls");
    }

    @Test
    void parsesBracketedAndTaggedToolCalls() {
        var rawTools = mapper.createArrayNode();
        rawTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        var plan = engine.plan(createRequest(rawTools, mapper.createObjectNode()));

        // Format 1: [TOOL_CALL: {...}]
        var bracketedText = "Here is the weather: [TOOL_CALL: {\"name\": \"get_weather\", \"arguments\": {\"city\": \"Shanghai\"}}]";
        var calls1 = engine.parse(bracketedText, plan);
        assertThat(calls1).hasSize(1);
        assertThat(calls1.get(0).name()).isEqualTo("get_weather");
        assertThat(calls1.get(0).arguments()).contains("\"city\":\"Shanghai\"");

        // Format 2: <tool_calls>[...]</tool_calls>
        var taggedText = "<tool_calls>[{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Tokyo\"}}]</tool_calls>";
        var calls2 = engine.parse(taggedText, plan);
        assertThat(calls2).hasSize(1);
        assertThat(calls2.get(0).name()).isEqualTo("get_weather");
        assertThat(calls2.get(0).arguments()).contains("\"city\":\"Tokyo\"");

        // Format 3: Markdown code block
        var fencedText = "```json\n{\"tool_calls\": [{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}]}\n```";
        var calls3 = engine.parse(fencedText, plan);
        assertThat(calls3).hasSize(1);
        assertThat(calls3.get(0).name()).isEqualTo("get_weather");
        assertThat(calls3.get(0).arguments()).contains("\"city\":\"Paris\"");
    }

    @Test
    void transformsStreamEmittingToolCallEvents() {
        var rawTools = mapper.createArrayNode();
        rawTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        var plan = engine.plan(createRequest(rawTools, mapper.createObjectNode()));

        var upstream = Flux.<CanonicalEvent>just(
            new CanonicalEvent.OutputTextDelta(1, "req-1", 1, "{\"tool_calls\": ["),
            new CanonicalEvent.OutputTextDelta(1, "req-1", 2, "{\"name\": \"get_weather\", "),
            new CanonicalEvent.OutputTextDelta(1, "req-1", 3, "\"arguments\": {\"city\": \"Shenzhen\"}}]}"),
            new CanonicalEvent.Completed(1, "req-1", 4, "stop")
        );

        var events = engine.transformStream("req-1", plan, upstream).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(CanonicalEvent.ToolCallStarted.class::isInstance);
        assertThat(events).anyMatch(CanonicalEvent.ToolArgumentsDelta.class::isInstance);
        assertThat(events).anyMatch(CanonicalEvent.ToolCallCompleted.class::isInstance);
        assertThat(events).anyMatch(e -> e instanceof CanonicalEvent.Completed c
            && "tool_calls".equals(c.finishReason()));
        assertThat(events).noneMatch(CanonicalEvent.OutputTextDelta.class::isInstance);
    }

    @Test
    void transformsStreamPassingProseNormally() {
        var rawTools = mapper.createArrayNode();
        rawTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        var plan = engine.plan(createRequest(rawTools, mapper.createObjectNode()));

        var upstream = Flux.<CanonicalEvent>just(
            new CanonicalEvent.OutputTextDelta(1, "req-2", 1, "Today's weather in Shanghai is sunny and warm."),
            new CanonicalEvent.OutputTextDelta(1, "req-2", 2, " Have a great day!"),
            new CanonicalEvent.Completed(1, "req-2", 3, "stop")
        );

        var events = engine.transformStream("req-2", plan, upstream).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(CanonicalEvent.OutputTextDelta.class::isInstance);
        assertThat(events).noneMatch(CanonicalEvent.ToolCallStarted.class::isInstance);
        assertThat(events).anyMatch(e -> e instanceof CanonicalEvent.Completed c
            && "stop".equals(c.finishReason()));
    }

    @Test
    void failsWhenRequiredToolCallNotProduced() {
        var rawTools = mapper.createArrayNode();
        rawTools.addObject().put("type", "function").putObject("function").put("name", "get_weather");
        var raw = mapper.createObjectNode().put("tool_choice", "required");
        var plan = engine.plan(createRequest(rawTools, raw));

        var upstream = Flux.<CanonicalEvent>just(
            new CanonicalEvent.OutputTextDelta(1, "req-3", 1, "Sorry, I cannot help with that."),
            new CanonicalEvent.Completed(1, "req-3", 2, "stop")
        );

        var events = engine.transformStream("req-3", plan, upstream).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(e -> e instanceof CanonicalEvent.Failed f
            && "tool_call_generation_failed".equals(f.errorType()));
    }

    @Test
    void preparesFunctionHistoryWithoutChangingTheCallerOrNativeSearch() {
        var raw = mapper.createObjectNode().put("tool_choice", "auto");
        var tools = mapper.createArrayNode();
        tools.addObject().put("type", "function").putObject("function").put("name", "inspect");
        tools.addObject().put("type", "web_search");
        var base = createRequest(tools, raw);
        var assistant = mapper.createObjectNode().put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "call_inspect")
            .putObject("function").put("name", "inspect").put("arguments", "{}");
        var result = mapper.createObjectNode().put("role", "tool")
            .put("tool_call_id", "call_inspect").put("content", "demo.txt");
        var request = new CanonicalRequest(base.requestId(), base.protocol(), base.providerId(),
            base.model(), base.stream(), List.of(base.messages().getFirst(), assistant, result),
            base.generation(), base.reasoning(), base.tools(), base.providerOptions(), raw);

        var prepared = engine.prepare(request, engine.plan(request));

        assertThat(prepared.tools()).hasSize(1);
        assertThat(prepared.tools().getFirst().path("type").asText()).isEqualTo("web_search");
        assertThat(prepared.messages().getLast().path("content").asText())
            .contains("Tool calling contract", "inspect");
        assertThat(prepared.messages().getLast().path("content").asText())
            .contains("call_inspect", "demo.txt");
        assertThat(prepared.messages().getLast().path("role").asText()).isEqualTo("user");
        assertThat(assistant.has("tool_calls")).isTrue();
        assertThat(result.path("role").asText()).isEqualTo("tool");
        assertThat(raw.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void producesAnEnforceableCanonicalStreamWithUsageAndToolEvents() {
        var tools = mapper.createArrayNode();
        tools.addObject().put("type", "function").putObject("function").put("name", "inspect");
        var request = createRequest(tools, mapper.createObjectNode().put("tool_choice", "required"));
        var events = Flux.<CanonicalEvent>just(
            new CanonicalEvent.ResponseStarted(1, request.requestId(), 0, "resp_test"),
            new CanonicalEvent.OutputTextDelta(1, request.requestId(), 1,
                "{\"tool_calls\":[{\"name\":\"inspect\",\"arguments\":{}}]}"),
            new CanonicalEvent.Usage(1, request.requestId(), 2, 4, 6, 0),
            new CanonicalEvent.Completed(1, request.requestId(), 3, "stop"));

        var transformed = com.any2api.protocol.CanonicalEventStream.enforce(request,
            engine.transformStream(request.requestId(), engine.plan(request), events))
            .collectList().block();

        assertThat(transformed).hasSize(6).anyMatch(CanonicalEvent.Usage.class::isInstance);
        assertThat(transformed.getLast()).isInstanceOf(CanonicalEvent.Completed.class);
    }

    @Test
    void placesTheContractInTheCurrentUserTurnWithoutRemovingMediaOrInstructions() {
        var tools = mapper.createArrayNode();
        tools.addObject().put("type", "function").putObject("function").put("name", "inspect");
        var base = createRequest(tools, mapper.createObjectNode().put("tool_choice", "required"));
        var system = mapper.createObjectNode().put("role", "system").put("content", "Be concise");
        var user = mapper.createObjectNode().put("role", "user");
        user.putArray("content").addObject().put("type", "input_text").put("text", "Inspect this");
        ((ArrayNode) user.path("content")).addObject().put("type", "input_image")
            .put("image_url", "data:image/png;base64,YQ==");
        var request = new CanonicalRequest(base.requestId(), base.protocol(), base.providerId(),
            base.model(), base.stream(), List.of(system, user), base.generation(),
            base.reasoning(), base.tools(), base.providerOptions(), base.rawRequest());

        var prepared = engine.prepare(request, engine.plan(request));

        assertThat(prepared.messages()).hasSize(2);
        assertThat(prepared.messages().getFirst()).isEqualTo(system);
        assertThat(prepared.messages().getLast().path("role").asText()).isEqualTo("user");
        assertThat(prepared.messages().getLast().path("content").get(1)).isEqualTo(user.path("content").get(1));
        assertThat(prepared.messages().getLast().path("content").get(2).path("text").asText())
            .contains("executed by the caller", "MUST produce");
        assertThat(user.path("content").size()).isEqualTo(2);
    }

    @Test
    void preservesNativeGenerationWhileConsumingGatewayToolAndSseControls() {
        var base = createRequest(mapper.createArrayNode(), mapper.createObjectNode());
        var request = new CanonicalRequest(base.requestId(), base.protocol(), base.providerId(),
            base.model(), base.stream(), base.messages(), Map.of("temperature", 0.4,
                "tool_choice", "none", "parallel_tool_calls", false,
                "stream_options", Map.of("include_usage", true)), base.reasoning(),
            base.tools(), base.providerOptions(), base.rawRequest());

        var prepared = engine.prepare(request, engine.plan(request));

        assertThat(prepared.generation()).containsOnlyKeys("temperature").containsEntry("temperature", 0.4);
        assertThat(request.generation()).hasSize(4);
    }

    @Test
    void failsInvalidArgumentsAndForbiddenParallelCallsWithoutLeakingJsonAsText() {
        var tools = mapper.createArrayNode();
        tools.addObject().put("type", "function").putObject("function").put("name", "inspect");
        var request = createRequest(tools, mapper.createObjectNode()
            .put("tool_choice", "required").put("parallel_tool_calls", false));
        for (var answer : List.of(
                "{\"tool_calls\":[{\"name\":\"inspect\",\"arguments\":\"broken\"}]}",
                "{\"tool_calls\":[{\"name\":\"inspect\",\"arguments\":{}},{\"name\":\"inspect\",\"arguments\":{}}]}")) {
            var events = engine.transformStream(request.requestId(), engine.plan(request), Flux.just(
                new CanonicalEvent.OutputTextDelta(1, request.requestId(), 0, answer),
                new CanonicalEvent.Completed(1, request.requestId(), 1, "stop"))).collectList().block();
            assertThat(events).singleElement().isInstanceOfSatisfying(CanonicalEvent.Failed.class,
                failed -> assertThat(failed.errorType()).isEqualTo("tool_call_generation_failed"));
        }
    }

    private CanonicalRequest createRequest(ArrayNode tools, ObjectNode raw) {
        var message = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var toolsList = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        tools.forEach(toolsList::add);
        return new CanonicalRequest(
            "test-req",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "test-provider",
            "test-model",
            true,
            List.of(message),
            Map.of(),
            Map.of(),
            toolsList,
            Map.of(),
            raw
        );
    }
}
