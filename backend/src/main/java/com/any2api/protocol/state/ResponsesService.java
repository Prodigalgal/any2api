package com.any2api.protocol.state;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyProtocol;
import com.any2api.auth.ApiKeyRequestFeatureDetector;
import com.any2api.config.Any2ApiProperties;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.routing.ResolvedRoute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ResponsesService {
    private static final Logger log = LoggerFactory.getLogger(ResponsesService.class);
    private final GatewayResponseStore store;
    private final ProviderResponseStateStore nativeStore;
    private final CanonicalRequestParser parser;
    private final OpenAiResponseWriter writer;
    private final ApiKeyAuthorization authorization;
    private final ApiKeyRequestFeatureDetector features;
    private final ObjectMapper mapper;
    private final Any2ApiProperties properties;
    private final ExecutorService databaseExecutor;

    public ResponsesService(GatewayResponseStore store, ProviderResponseStateStore nativeStore,
        CanonicalRequestParser parser, OpenAiResponseWriter writer, ApiKeyAuthorization authorization,
        ApiKeyRequestFeatureDetector features, ObjectMapper mapper, Any2ApiProperties properties,
        ExecutorService databaseExecutor) {
        this.store = store;
        this.nativeStore = nativeStore;
        this.parser = parser;
        this.writer = writer;
        this.authorization = authorization;
        this.features = features;
        this.mapper = mapper;
        this.properties = properties;
        this.databaseExecutor = databaseExecutor;
    }

    public record Prepared(CanonicalRequest responseRequest, CanonicalRequest inferenceRequest) {}

    public Mono<ResolvedRoute> storedContinuationRoute(ObjectNode request, ApiKeyGrant grant) {
        var previousId = request.path("previous_response_id").asText("");
        if (!previousId.startsWith("resp_gw_")) return Mono.empty();
        return owned(previousId, null, grant).map(state -> new ResolvedRoute(state.providerId(), state.model()));
    }

    public Mono<Prepared> prepare(ObjectNode request, ResolvedRoute route, ApiKeyGrant grant, String requestId) {
        return Mono.fromCallable(() -> {
            var raw = request.deepCopy();
            var previousId = raw.path("previous_response_id").asText("");
            var replayed = false;
            if (!previousId.isBlank()) {
                var previous = store.find(previousId, owner(grant));
                if (previous.isPresent()) {
                    var state = previous.get();
                    authorize(state, grant, route.providerId());
                    if (!state.model().equals(route.upstreamModel())) {
                        throw OpenAiRequestException.conflict("model", "continuation must use the original provider/model");
                    }
                    if (!java.util.Set.of("completed", "incomplete").contains(state.response().path("status").asText())) {
                        throw OpenAiRequestException.invalid("previous_response_id", "response is not available for continuation");
                    }
                    var input = mapper.createArrayNode();
                    state.input().forEach(item -> input.add(item.deepCopy()));
                    state.response().path("output").forEach(item -> input.add(item.deepCopy()));
                    inputItems(raw.path("input")).forEach(input::add);
                    raw.set("input", input);
                    replayed = true;
                } else {
                    var state = nativeStore.find(route.providerId(), previousId)
                        .filter(value -> Objects.equals(value.apiKeyId(), grant.keyId()))
                        .orElseThrow(ResponseNotFoundException::new);
                    if (!grant.allowsModel(state.providerId(), route.upstreamModel())) throw new ResponseNotFoundException();
                    authorization.requireFeatures(grant, features.requiredFeatures(state.state()));
                    if (state.state().hasNonNull("model") && !route.upstreamModel().equals(state.state().path("model").asText())) {
                        throw OpenAiRequestException.conflict("model", "native continuation must use the original model");
                    }
                }
            }
            authorization.requireFeatures(grant, features.requiredFeatures(raw));
            var responseRequest = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, raw, requestId);
            var providerRaw = raw.deepCopy();
            if (replayed) providerRaw.remove("previous_response_id");
            // Public store semantics are implemented by this service, independently of native provider state.
            var inferenceRequest = parser.parse(CanonicalRequest.Protocol.RESPONSES, route, providerRaw, requestId);
            return new Prepared(responseRequest, inferenceRequest);
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Flux<CanonicalEvent> track(CanonicalRequest request, ApiKeyGrant grant, Flux<CanonicalEvent> events) {
        if (!request.rawRequest().path("store").asBoolean(false)) return events;
        return Flux.defer(() -> {
            var capture = writer.capture(request);
            var started = new java.util.concurrent.atomic.AtomicBoolean();
            var startEvent = new java.util.concurrent.atomic.AtomicReference<CanonicalEvent.ResponseStarted>();
            var initialSaveFinished = new java.util.concurrent.CompletableFuture<Void>();
            var publicId = "resp_gw_" + UUID.randomUUID().toString().replace("-", "");
            var input = inputItems(request.rawRequest().path("input"));
            var payloadBytes = new AtomicLong(mapper.writeValueAsBytes(input).length);
            var argumentBytes = new java.util.HashMap<String, Long>();
            return events.concatMap(event -> {
                CanonicalEvent publicEvent = event instanceof CanonicalEvent.ResponseStarted upstreamStart
                    ? new CanonicalEvent.ResponseStarted(upstreamStart.schemaVersion(), upstreamStart.requestId(), upstreamStart.sequenceNumber(), publicId, upstreamStart.createdAt())
                    : event;
                if (publicEvent instanceof CanonicalEvent.OutputTextDelta text) payloadBytes.addAndGet(utf8Bytes(text.delta()));
                if (publicEvent instanceof CanonicalEvent.ReasoningDelta text) payloadBytes.addAndGet(utf8Bytes(text.delta()));
                if (publicEvent instanceof CanonicalEvent.ToolArgumentsDelta text) {
                    var bytes = utf8Bytes(text.delta());
                    argumentBytes.merge(text.toolCallId(), bytes, Long::sum);
                    payloadBytes.addAndGet(bytes);
                }
                if (publicEvent instanceof CanonicalEvent.ToolCallCompleted tool
                    && argumentBytes.getOrDefault(tool.toolCallId(), 0L) == 0) payloadBytes.addAndGet(utf8Bytes(tool.arguments()));
                if (payloadBytes.get() > properties.getResponses().getMaxStateBytes()) {
                    return Mono.error(OpenAiRequestException.invalid("store", "stored response exceeds the configured size limit"));
                }
                capture.accept(publicEvent);
                if (publicEvent instanceof CanonicalEvent.ResponseStarted responseStarted) {
                    started.set(true);
                    startEvent.set(responseStarted);
                }
                if (!started.get() || !(publicEvent instanceof CanonicalEvent.ResponseStarted
                    || publicEvent instanceof CanonicalEvent.Completed || publicEvent instanceof CanonicalEvent.Failed)) {
                    return Mono.just(publicEvent);
                }
                var document = capture.document();
                if (publicEvent instanceof CanonicalEvent.ResponseStarted) {
                    document.put("status", "in_progress").set("output", mapper.createArrayNode());
                }
                return Mono.fromRunnable(() -> {
                    var initial = publicEvent instanceof CanonicalEvent.ResponseStarted;
                    try { persist(request, grant, input, document, initial); }
                    finally { if (initial) initialSaveFinished.complete(null); }
                })
                    .subscribeOn(Schedulers.fromExecutor(databaseExecutor)).thenReturn(publicEvent);
            }).onErrorResume(error -> {
                if (!started.get()) return Flux.error(error);
                var failure = writer.normalizeFailure(request, error);
                capture.accept(failure);
                return Mono.fromRunnable(() -> {
                    ObjectNode failed;
                    try { failed = capture.document(); }
                    catch (RuntimeException malformedOutput) {
                        failed = writer.responseDocument(request, java.util.List.of(startEvent.get(), failure));
                    }
                    if (mapper.writeValueAsBytes(failed).length + mapper.writeValueAsBytes(input).length
                        > properties.getResponses().getMaxStateBytes()) {
                        failed.set("output", mapper.createArrayNode());
                    }
                    persist(request, grant, input, failed, false);
                }).subscribeOn(Schedulers.fromExecutor(databaseExecutor))
                    .onErrorResume(saveError -> {
                        log.warn("response_state_error_save_failed request_id={}", request.requestId(), saveError);
                        return Mono.empty();
                    }).thenMany(Flux.error(error));
            }).doFinally(signal -> {
                if (signal == reactor.core.publisher.SignalType.CANCEL && started.get()) {
                    var cancelled = capture.document();
                    cancelled.put("status", "cancelled");
                    initialSaveFinished.thenRunAsync(() -> {
                        try { persist(request, grant, input, cancelled, false); }
                        catch (RuntimeException error) {
                            log.warn("response_state_cancel_save_failed request_id={}", request.requestId(), error);
                        }
                    }, databaseExecutor);
                }
            });
        });
    }

    private void persist(CanonicalRequest request, ApiKeyGrant grant, ArrayNode input, ObjectNode response, boolean initial) {
        if (mapper.writeValueAsBytes(response).length + mapper.writeValueAsBytes(input).length
            > properties.getResponses().getMaxStateBytes()) {
            throw OpenAiRequestException.invalid("store", "stored response exceeds the configured size limit");
        }
        try {
            if (initial) store.save(owner(grant), grant.keyId(), request.providerId(), request.model(), input, response,
                Instant.now().plus(properties.getResponses().getRetention()), properties.getResponses().getMaxStoredPerKey());
            else store.updateProgress(owner(grant), request.providerId(), request.model(), response);
        } catch (RuntimeException error) {
            log.warn("response_state_save_failed request_id={} provider={} model={}",
                request.requestId(), request.providerId(), request.model(), error);
            throw error;
        }
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public Mono<ObjectNode> retrieve(String id, String providerHint, ApiKeyGrant grant) {
        return owned(id, providerHint, grant).map(state -> (ObjectNode) state.response().deepCopy());
    }

    public Mono<ObjectNode> delete(String id, String providerHint, ApiKeyGrant grant) {
        return owned(id, providerHint, grant).flatMap(state -> Mono.fromCallable(() -> {
            if (!store.delete(id, owner(grant))) throw new ResponseNotFoundException();
            return mapper.createObjectNode().put("id", id).put("object", "response.deleted").put("deleted", true);
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor)));
    }

    public Mono<ObjectNode> listInput(String id, String providerHint, ApiKeyGrant grant,
        int limit, String order, String after, String before) {
        if (limit < 1 || limit > 100 || !java.util.Set.of("asc", "desc").contains(order)) {
            return Mono.error(OpenAiRequestException.invalid("limit,order", "limit must be 1..100 and order asc or desc"));
        }
        if (after != null && before != null) {
            return Mono.error(OpenAiRequestException.conflict("after,before", "only one pagination cursor may be supplied"));
        }
        return owned(id, providerHint, grant).map(state -> {
            var items = new ArrayList<JsonNode>();
            state.input().forEach(items::add);
            if ("desc".equals(order)) java.util.Collections.reverse(items);
            var start = 0;
            var end = items.size();
            if (after != null || before != null) {
                var cursor = after != null ? after : before;
                var cursorIndex = java.util.stream.IntStream.range(0, items.size())
                    .filter(index -> cursor.equals(items.get(index).path("id").asText())).findFirst()
                    .orElseThrow(() -> OpenAiRequestException.invalid("after,before", "input item cursor does not exist"));
                if (after != null) start = cursorIndex + 1;
                else end = cursorIndex;
            }
            var data = mapper.createArrayNode();
            var pageEnd = Math.min(end, start + limit);
            for (var index = start; index < pageEnd; index++) data.add(resourceInputItem(items.get(index)));
            var page = mapper.createObjectNode().put("object", "list").put("has_more", pageEnd < end).set("data", data);
            if (data.isEmpty()) page.putNull("first_id").putNull("last_id");
            else page.put("first_id", data.get(0).path("id").asText()).put("last_id", data.get(data.size()-1).path("id").asText());
            return page;
        });
    }

    private JsonNode resourceInputItem(JsonNode source) {
        // Project legacy easy-message input for the SDK without rewriting continuation state.
        var item = source.deepCopy();
        if (!(item instanceof ObjectNode message)) return item;
        var type = message.path("type").asText();
        if (java.util.Set.of("message", "function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output").contains(type)
            && !message.hasNonNull("status")) message.put("status", "completed");
        if (!"message".equals(type)) return message;
        var assistant = "assistant".equals(message.path("role").asText());
        var content = message.path("content");
        var blocks = content.isArray() ? (ArrayNode) content : mapper.createArrayNode();
        if (content.isTextual()) blocks.add(mapper.createObjectNode().put("text", content.asText()));
        if (content.isTextual() || content.isArray() || content.isNull() || content.isMissingNode()) {
            for (var block : blocks) {
                if (block instanceof ObjectNode text && text.has("text")
                    && java.util.Set.of("", "text", "input_text", "output_text").contains(text.path("type").asText())) {
                    text.put("type", assistant ? "output_text" : "input_text");
                    if (assistant && !text.hasNonNull("annotations")) text.putArray("annotations");
                }
            }
            message.set("content", blocks);
        }
        return message;
    }

    private Mono<GatewayResponseStore.StoredResponse> owned(String id, String providerHint, ApiKeyGrant grant) {
        return Mono.fromCallable(() -> {
            var state = store.find(id, owner(grant)).orElseThrow(ResponseNotFoundException::new);
            authorize(state, grant, providerHint);
            return state;
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    private void authorize(GatewayResponseStore.StoredResponse state, ApiKeyGrant grant, String providerHint) {
        if (providerHint != null && !providerHint.equals(state.providerId())) throw new ResponseNotFoundException();
        authorization.require(grant, ApiKeyProtocol.RESPONSES, state.providerId(), state.model());
        var history = mapper.createObjectNode().set("input", state.input());
        authorization.requireFeatures(grant, features.requiredFeatures(history));
        authorization.requireFeatures(grant, features.requiredFeatures((ObjectNode) state.response()));
    }

    private ArrayNode inputItems(JsonNode input) {
        var items = mapper.createArrayNode();
        if (input.isMissingNode() || input.isNull()) return items;
        var source = input.isArray() ? input : mapper.createArrayNode().add(input);
        for (var item : source) {
            ObjectNode normalized;
            if (item.isTextual()) {
                normalized = mapper.createObjectNode().put("type", "message").put("role", "user").set("content", item.deepCopy());
            } else if (item.isObject()) normalized = (ObjectNode) item.deepCopy();
            else throw OpenAiRequestException.invalid("input", "input items must be messages or strings");
            if (!normalized.hasNonNull("id")) normalized.put("id", "in_" + UUID.randomUUID().toString().replace("-", ""));
            if (!normalized.hasNonNull("type") && normalized.has("role")) normalized.put("type", "message");
            items.add(normalized);
        }
        return items;
    }

    public static String owner(ApiKeyGrant grant) {
        if (grant.keyId() != null) return "key:" + grant.keyId();
        if (grant.fullAccess()) return "system";
        throw new IllegalStateException("persistent caller identity is unavailable");
    }
}
