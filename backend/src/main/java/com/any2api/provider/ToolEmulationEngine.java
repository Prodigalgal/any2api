package com.any2api.provider;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Component
public class ToolEmulationEngine {
    private static final int MAX_TOOLS = 128;
    private static final int MAX_CAPTURE_CHARS = 1 << 20;
    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern FENCED_JSON = Pattern.compile(
        "```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGGED_TOOL_CALLS = Pattern.compile(
        "<tool_calls>\\s*([\\s\\S]*?)\\s*</tool_calls>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETED_TOOL_CALL = Pattern.compile(
        "\\[TOOL_CALL:\\s*([\\s\\S]*?)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCLOSED_TOOL_CALL = Pattern.compile(
        "\\[TOOL_CALL\\]\\s*([\\s\\S]*?)\\s*\\[/TOOL_CALL\\]", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;

    public ToolEmulationEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Plan plan(CanonicalRequest request) {
        var tools = normalize(request.tools().stream()
            .filter(tool -> "function".equals(tool.path("type").asText("function")))
            .toList());
        var choice = choice(request.rawRequest().path("tool_choice"), tools);
        if (tools.isEmpty() && choice.required()) {
            throw new IllegalArgumentException("tool_choice requires at least one function tool");
        }
        var parallel = !request.rawRequest().path("parallel_tool_calls").isBoolean()
            || request.rawRequest().path("parallel_tool_calls").asBoolean();
        return new Plan(tools, choice, parallel);
    }

    public CanonicalRequest prepare(CanonicalRequest request, Plan plan) {
        var messages = new ArrayList<JsonNode>();
        for (var source : request.messages()) {
            var message = (tools.jackson.databind.node.ObjectNode) source.deepCopy();
            var calls = message.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                prependText(message, "Previous assistant function calls (JSON): " + calls);
                message.remove("tool_calls");
            }
            var callId = message.path("tool_call_id").asText("");
            if (!callId.isBlank()) {
                prependText(message, "[Function result call_id=" + callId + "]");
                // These Web transports have no native function-result role.
                message.put("role", "user");
            }
            messages.add(message);
        }
        if (plan.enabled()) {
            // Web agents may discard standalone system/developer messages. Keep the
            // caller-owned function contract in the actual user turn sent upstream.
            var lastUser = -1;
            for (var index = 0; index < messages.size(); index++) {
                if ("user".equals(messages.get(index).path("role").asText(""))) lastUser = index;
            }
            if (lastUser < 0) {
                messages.add(mapper.createObjectNode().put("role", "user")
                    .put("content", appendContract("", plan)));
            } else {
                var message = (tools.jackson.databind.node.ObjectNode) messages.get(lastUser);
                if (message.path("content").isArray()) {
                    ((ArrayNode) message.path("content")).add(mapper.createObjectNode()
                        .put("type", "input_text").put("text", appendContract("", plan)));
                } else {
                    message.put("content", appendContract(message.path("content").asText(""), plan));
                }
            }
        }
        var nativeTools = request.tools().stream()
            .filter(tool -> !"function".equals(tool.path("type").asText("function")))
            .toList();
        var raw = (tools.jackson.databind.node.ObjectNode) request.rawRequest().deepCopy();
        raw.set("tools", mapper.valueToTree(nativeTools));
        raw.remove("parallel_tool_calls");
        if (!plan.tools().isEmpty() && !plan.choice().disabled()) raw.remove("tool_choice");
        return new CanonicalRequest(request.requestId(), request.protocol(), request.providerId(),
            request.model(), request.stream(), List.copyOf(messages), request.generation(),
            request.reasoning(), nativeTools, request.providerOptions(), raw);
    }

    private void prependText(tools.jackson.databind.node.ObjectNode message, String prefix) {
        var content = message.path("content");
        if (content.isArray()) {
            var blocks = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("type", "input_text").put("text", prefix));
            content.forEach(part -> blocks.add(part.deepCopy()));
            message.set("content", blocks);
        } else {
            message.put("content", prefix + (content.asText("").isBlank()
                ? "" : "\n" + content.asText("")));
        }
    }

    public String appendContract(String prompt, Plan plan) {
        if (!plan.enabled()) return prompt;
        var definitions = mapper.createArrayNode();
        for (var tool : plan.tools()) {
            definitions.add(mapper.createObjectNode()
                .put("name", tool.name())
                .put("description", tool.description())
                .set("parameters", tool.parameters().deepCopy()));
        }
        var contract = """
            [Tool calling contract]
            Available tools: %s
            Tool choice: %s. Parallel calls allowed: %s.
            These functions belong to the caller and are executed by the caller after your reply.
            You are generating a function invocation, not executing the function yourself.
            For required or a named tool, you MUST produce an available tool call, not prose.
            Use only declared tool names and valid JSON object arguments.
            When a tool is needed, output only this JSON object and no prose:
            {"tool_calls":[{"name":"tool_name","arguments":{}}]}
            Alternative supported format:
            [TOOL_CALL: {"name":"tool_name","arguments":{}}]
            When no tool is needed, answer normally without a tool_calls object.
            """.formatted(mapper.writeValueAsString(definitions), plan.choice().label(),
                plan.parallel()).trim();
        return prompt.isBlank() ? contract : prompt.trim() + "\n\n" + contract;
    }

    public List<ToolCall> parse(String text, Plan plan) {
        if (!plan.enabled()) return List.of();
        var allowed = plan.choice().named()
            ? Set.of(plan.choice().label())
            : plan.tools().stream().map(Tool::name).collect(java.util.stream.Collectors.toSet());

        for (var candidate : candidates(text)) {
            try {
                var value = mapper.readTree(candidate);
                var calls = resolveCallsNode(value);
                if (calls == null) continue;

                var output = new ArrayList<ToolCall>();
                for (var call : calls) {
                    var function = call.path("function").isObject()
                        ? call.path("function") : call;
                    var name = function.path("name").asText("").trim();
                    if (!allowed.contains(name)) continue;

                    var arguments = function.path("arguments");
                    if (arguments.isMissingNode() && function.has("parameters")) {
                        arguments = function.path("parameters");
                    }
                    if (arguments.isTextual()) {
                        try {
                            arguments = mapper.readTree(arguments.asText());
                        } catch (RuntimeException ignored) {
                            throw new IllegalArgumentException(
                                "Emulated tool emitted invalid JSON arguments for " + name);
                        }
                    }
                    if (!arguments.isObject()) {
                        throw new IllegalArgumentException(
                            "Emulated tool emitted non-object arguments for " + name);
                    }
                    var id = call.path("id").asText("").trim();
                    if (id.isBlank()) {
                        id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    }
                    output.add(new ToolCall(id, name, mapper.writeValueAsString(arguments)));
                }
                if (!output.isEmpty()) {
                    return List.copyOf(output);
                }
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (RuntimeException ignored) {
                // Try the next candidate.
            }
        }
        return List.of();
    }

    private ArrayNode resolveCallsNode(JsonNode value) {
        if (value.isArray()) {
            return (ArrayNode) value;
        }
        if (value.path("tool_calls").isArray()) {
            return (ArrayNode) value.path("tool_calls");
        }
        if (value.has("function_call")) {
            return mapper.createArrayNode().add(value.path("function_call"));
        }
        if (value.isObject() && value.has("name") && (value.has("arguments") || value.has("parameters"))) {
            return mapper.createArrayNode().add(value);
        }
        return null;
    }

    public Flux<CanonicalEvent> transformStream(String requestId, Plan plan, Flux<CanonicalEvent> upstream) {
        if (!plan.enabled()) {
            return upstream;
        }
        return Flux.defer(() -> {
            var buffer = new StringBuilder();
            var sequence = new AtomicLong(1000);
            var isBuffering = new AtomicBoolean(true);
            var toolCallsEmitted = new AtomicBoolean(false);

            return upstream.concatMap(event -> {
                if (event instanceof CanonicalEvent.OutputTextDelta delta) {
                    if (isBuffering.get()) {
                        buffer.append(delta.delta());
                        if (buffer.length() > MAX_CAPTURE_CHARS) {
                            return Flux.just(new CanonicalEvent.Failed(
                                delta.schemaVersion(), requestId, delta.sequenceNumber(),
                                "tool_call_generation_failed", "Emulated tool output exceeds capture limit", Map.of()));
                        }
                        var current = buffer.toString().stripLeading();
                        if (!plan.required() && looksDefinitelyLikeProse(current)) {
                            isBuffering.set(false);
                            var flushed = buffer.toString();
                            buffer.setLength(0);
                            return Flux.just(new CanonicalEvent.OutputTextDelta(
                                delta.schemaVersion(), requestId, delta.sequenceNumber(), flushed));
                        }
                        return Flux.empty();
                    } else {
                        return Flux.just(event);
                    }
                }

                if (event instanceof CanonicalEvent.Completed completed) {
                    var answer = buffer.toString();
                    buffer.setLength(0);

                    if (isBuffering.get()) {
                        List<ToolCall> calls;
                        try {
                            calls = parse(answer, plan);
                        } catch (IllegalArgumentException error) {
                            return Flux.just(new CanonicalEvent.Failed(
                                completed.schemaVersion(), requestId, completed.sequenceNumber(),
                                "tool_call_generation_failed", error.getMessage(), Map.of()));
                        }
                        if (!calls.isEmpty()) {
                            if (!plan.parallel() && calls.size() > 1) {
                                return Flux.just(new CanonicalEvent.Failed(
                                    completed.schemaVersion(), requestId, completed.sequenceNumber(),
                                    "tool_call_generation_failed",
                                    "Emulated model produced parallel calls while parallel_tool_calls=false",
                                    Map.of()));
                            }
                            var toolEvents = new ArrayList<CanonicalEvent>();
                            for (var call : calls) {
                                toolEvents.add(new CanonicalEvent.ToolCallStarted(
                                    completed.schemaVersion(), requestId, sequence.incrementAndGet(),
                                    call.id(), call.name()));
                                toolEvents.add(new CanonicalEvent.ToolArgumentsDelta(
                                    completed.schemaVersion(), requestId, sequence.incrementAndGet(),
                                    call.id(), call.arguments()));
                                toolEvents.add(new CanonicalEvent.ToolCallCompleted(
                                    completed.schemaVersion(), requestId, sequence.incrementAndGet(),
                                    call.id(), call.arguments()));
                            }
                            toolCallsEmitted.set(true);
                            toolEvents.add(new CanonicalEvent.Completed(
                                completed.schemaVersion(), requestId, completed.sequenceNumber(),
                                "tool_calls"));
                            return Flux.fromIterable(toolEvents);
                        }

                        if (plan.required()) {
                            return Flux.just(new CanonicalEvent.Failed(
                                completed.schemaVersion(), requestId, completed.sequenceNumber(),
                                "tool_call_generation_failed",
                                "Emulated model did not produce the required function tool call",
                                Map.of()));
                        }

                        if (!answer.isBlank()) {
                            return Flux.just(
                                new CanonicalEvent.OutputTextDelta(
                                    completed.schemaVersion(), requestId, sequence.incrementAndGet(), answer),
                                completed);
                        }
                    } else if (plan.required() && !toolCallsEmitted.get()) {
                        return Flux.just(new CanonicalEvent.Failed(
                            completed.schemaVersion(), requestId, completed.sequenceNumber(),
                            "tool_call_generation_failed",
                            "Emulated model did not produce the required function tool call",
                            Map.of()));
                    }
                    return Flux.just(event);
                }

                return Flux.just(event);
            }).map(event -> resequence(event, sequence.incrementAndGet()))
                .takeUntil(event -> event instanceof CanonicalEvent.Completed
                || event instanceof CanonicalEvent.Failed);
        });
    }

    private CanonicalEvent resequence(CanonicalEvent event, long sequence) {
        var version = event.schemaVersion();
        var requestId = event.requestId();
        return switch (event) {
            case CanonicalEvent.ResponseStarted started -> new CanonicalEvent.ResponseStarted(
                version, requestId, sequence, started.responseId(), started.createdAt());
            case CanonicalEvent.ReasoningDelta delta -> new CanonicalEvent.ReasoningDelta(
                version, requestId, sequence, delta.delta());
            case CanonicalEvent.OutputTextDelta delta -> new CanonicalEvent.OutputTextDelta(
                version, requestId, sequence, delta.delta());
            case CanonicalEvent.ToolCallStarted call -> new CanonicalEvent.ToolCallStarted(
                version, requestId, sequence, call.toolCallId(), call.name());
            case CanonicalEvent.ToolArgumentsDelta delta -> new CanonicalEvent.ToolArgumentsDelta(
                version, requestId, sequence, delta.toolCallId(), delta.delta());
            case CanonicalEvent.ToolCallCompleted call -> new CanonicalEvent.ToolCallCompleted(
                version, requestId, sequence, call.toolCallId(), call.arguments());
            case CanonicalEvent.Usage usage -> new CanonicalEvent.Usage(
                version, requestId, sequence, usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadTokens(), usage.source(), usage.rawInputTokens(),
                usage.rawOutputTokens(), usage.rawCacheReadTokens());
            case CanonicalEvent.Completed completed -> new CanonicalEvent.Completed(
                version, requestId, sequence, completed.finishReason());
            case CanonicalEvent.Failed failed -> new CanonicalEvent.Failed(
                version, requestId, sequence, failed.errorType(), failed.message(), failed.detail());
        };
    }

    private boolean looksDefinitelyLikeProse(String text) {
        if (text.isEmpty()) return false;
        var first = text.charAt(0);
        if (first == '{' || first == '[' || first == '`' || first == '<') {
            return false;
        }
        if (text.startsWith("[TOOL_CALL")) {
            return false;
        }
        return text.length() >= 15;
    }

    private List<Tool> normalize(List<JsonNode> rawTools) {
        if (rawTools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("Emulated tools support at most " + MAX_TOOLS + " tools");
        }
        var output = new ArrayList<Tool>();
        var names = new java.util.HashSet<String>();
        for (var raw : rawTools) {
            var type = raw.path("type").asText("function");
            if (!"function".equals(type)) {
                throw new IllegalArgumentException("Emulated tools support only function tools");
            }
            var definition = raw.path("function").isObject() ? raw.path("function") : raw;
            var name = definition.path("name").asText("").trim();
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("function tool name is invalid");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate function tool: " + name);
            }
            if (definition.path("strict").asBoolean(false)) {
                throw new IllegalArgumentException("Emulated tools do not support strict=true");
            }
            var parameters = definition.path("parameters").isObject()
                ? definition.path("parameters").deepCopy()
                : mapper.createObjectNode().put("type", "object")
                    .set("properties", mapper.createObjectNode());
            output.add(new Tool(name, definition.path("description").asText(""), parameters));
        }
        return List.copyOf(output);
    }

    private Choice choice(JsonNode raw, List<Tool> tools) {
        if (raw.isMissingNode() || raw.isNull()) {
            return new Choice("auto", false, false, false);
        }
        if (raw.isTextual()) {
            return switch (raw.asText().toLowerCase()) {
                case "auto" -> new Choice("auto", false, false, false);
                case "none" -> new Choice("none", false, true, false);
                case "required", "any" -> new Choice("required", true, false, false);
                default -> throw new IllegalArgumentException(
                    "tool_choice must be auto, none, required, any, or a function object");
            };
        }
        if (!raw.isObject() || !"function".equals(raw.path("type").asText(""))) {
            throw new IllegalArgumentException("tool_choice has an invalid shape");
        }
        var name = raw.path("function").path("name").asText(raw.path("name").asText(""));
        if (tools.stream().noneMatch(tool -> tool.name().equals(name))) {
            throw new IllegalArgumentException("tool_choice references an undeclared function");
        }
        return new Choice(name, true, false, true);
    }

    private List<String> candidates(String text) {
        var raw = text == null ? "" : text.trim();
        var output = new LinkedHashSet<String>();
        if (!raw.isBlank()) output.add(raw);

        var fenced = FENCED_JSON.matcher(raw);
        while (fenced.find()) output.add(fenced.group(1).trim());

        var tagged = TAGGED_TOOL_CALLS.matcher(raw);
        if (tagged.find()) output.add(tagged.group(1).trim());

        var bracketed = BRACKETED_TOOL_CALL.matcher(raw);
        while (bracketed.find()) output.add(bracketed.group(1).trim());

        var enclosed = ENCLOSED_TOOL_CALL.matcher(raw);
        while (enclosed.find()) output.add(enclosed.group(1).trim());

        var start = raw.indexOf('{');
        var end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) output.add(raw.substring(start, end + 1));

        return List.copyOf(output);
    }

    public record Plan(List<Tool> tools, Choice choice, boolean parallel) {
        public boolean enabled() { return !tools.isEmpty() && !choice.disabled(); }
        public boolean required() { return choice.required(); }
    }

    public record Tool(String name, String description, JsonNode parameters) {}
    public record ToolCall(String id, String name, String arguments) {}
    public record Choice(String label, boolean required, boolean disabled, boolean named) {}
}
