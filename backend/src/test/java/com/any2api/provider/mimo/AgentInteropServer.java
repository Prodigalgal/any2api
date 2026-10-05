package com.any2api.provider.mimo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.api.ApiExceptionHandler;
import com.any2api.api.openai.OpenAiGatewayController;
import com.any2api.api.openai.ResponsesResourceController;
import com.any2api.api.openai.ModelsController;
import com.any2api.auth.ApiKeyAuthenticator;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyRateLimiter;
import com.any2api.auth.ApiKeyRequestFeatureDetector;
import com.any2api.auth.PublicApiKeyWebFilter;
import com.any2api.config.Any2ApiProperties;
import com.any2api.config.SecurityConfiguration;
import com.any2api.interop.InteropDatabase;
import com.any2api.observability.RequestIdWebFilter;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalEventStream;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiRequestException;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.protocol.OpenAiToolBridge;
import com.any2api.protocol.SmartContextWindowManager;
import com.any2api.protocol.state.ProviderResponseStateStore;
import com.any2api.protocol.state.ResponsesService;
import com.any2api.provider.InferenceCoordinator;
import com.any2api.provider.ModelCatalogCache;
import com.any2api.provider.ModelRuntimeGuard;
import com.any2api.provider.DiscoveredModel;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.ProviderRequestValidation;
import com.any2api.provider.ProviderTransportMode;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.routing.ProviderRouteResolver;
import com.any2api.routing.RandomInferenceRouter;
import com.any2api.transport.OfficialBrowserSemanticCommandFactory;
import com.any2api.transport.OfficialBrowserTransportClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

/** Test-only gateway with real public controllers and PostgreSQL; upstream generation is deterministic. */
public final class AgentInteropServer {
    private static final List<Map<String,Object>> REQUESTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static reactor.netty.DisposableServer server;

    @Configuration @EnableWebFlux @EnableWebFluxSecurity static class WebConfiguration {}

    @RestController static class FixtureControl {
        @GetMapping("/__fixture/health") public Map<String,Object> health() {
            return Map.of("status","ready","upstream","fixture","version","0.27.7");
        }
        @GetMapping("/__fixture/requests") public List<Map<String,Object>> requests() { return List.copyOf(REQUESTS); }
        @PostMapping("/__fixture/shutdown") public Map<String,Boolean> shutdown() {
            reactor.core.scheduler.Schedulers.parallel().schedule(() -> server.dispose(),100,java.util.concurrent.TimeUnit.MILLISECONDS);
            return Map.of("stopping",true);
        }
    }

    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var properties = new Any2ApiProperties();
        var primary = new ApiKeyGrant(InteropDatabase.PRIMARY_KEY,"fixture-primary",Map.of(),Set.of(),Set.of(),null,true);
        var other = new ApiKeyGrant(InteropDatabase.OTHER_KEY,"fixture-other",Map.of(),Set.of(),Set.of(),null,true);
        try(var database = new InteropDatabase(); var executor = Executors.newVirtualThreadPerTaskExecutor();
            var context = new AnnotationConfigApplicationContext()) {
            var writer = new OpenAiResponseWriter(mapper);
            var parser = new CanonicalRequestParser(mapper);
            var authorization = new ApiKeyAuthorization();
            var features = new ApiKeyRequestFeatureDetector();
            var responses = new ResponsesService(database.store(mapper),new ProviderResponseStateStore(database.jdbc,mapper),
                parser,writer,authorization,features,mapper,properties,executor);
            var mimoMapper = new MimoRequestMapper(mapper);
            var provider = new MimoProvider(mock(OfficialBrowserTransportClient.class),new OfficialBrowserSemanticCommandFactory(mapper),
                mock(ProxyPoolService.class),new MimoProperties(),mimoMapper,mapper);
            var registry = ProviderRegistry.allEnabled(List.of(provider));
            var catalog = mock(ModelCatalogCache.class);
            var models = List.of("fixture", "fixture-codex", "fixture-codex-image").stream().map(model -> {
                var capabilities = mapper.valueToTree(provider.modelContract(new DiscoveredModel(model, model, Map.of())).asMap());
                return new ModelCatalogCache.Entry(model, model, "mimo", "MiMo", capabilities, capabilities,
                    null, null, null, "fixture", mapper.createObjectNode(), List.of(), 1, true, "READY",
                    1, 1, 0, 0, 0, 1.0, 0, 0, null, null, "PASSED", null, null);
            }).toList();
            when(catalog.list()).thenReturn(Mono.just(models));
            var runtimeGuard = mock(ModelRuntimeGuard.class);
            when(runtimeGuard.callable(any(), any())).thenReturn(true);
            when(runtimeGuard.snapshot(any(), any())).thenReturn(new ModelRuntimeGuard.Snapshot(0, 0, "CLOSED", 0, 0));
            var coordinator = mock(InferenceCoordinator.class);
            when(coordinator.execute(any(CanonicalRequest.class),any(UUID.class),any(ProviderTransportMode.class)))
                .thenAnswer(call -> fixture(call.getArgument(0),provider,mimoMapper,mapper));
            var authenticator = mock(ApiKeyAuthenticator.class);
            when(authenticator.authenticate(any())).thenAnswer(call -> Mono.just(switch((String)call.getArgument(0)) {
                case "fixture-primary" -> Optional.of(primary);
                case "fixture-other" -> Optional.of(other);
                default -> Optional.empty();
            }));
            context.register(WebConfiguration.class, SecurityConfiguration.class);
            context.registerBean(Any2ApiProperties.class,()->properties);
            var sessions = mock(com.any2api.auth.AdminSessionService.class);
            when(sessions.verify(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(Optional.empty());
            context.registerBean(com.any2api.auth.AdminSessionWebFilter.class,
                ()->new com.any2api.auth.AdminSessionWebFilter(sessions));
            context.registerBean(ObjectMapper.class,()->mapper);
            context.registerBean(OpenAiGatewayController.class,()->new OpenAiGatewayController(new ProviderRouteResolver(registry),
                parser,coordinator,writer,mock(RandomInferenceRouter.class),authorization,features,responses));
            context.registerBean(ResponsesResourceController.class,()->new ResponsesResourceController(responses,authorization));
            context.registerBean(ModelsController.class,()->new ModelsController(registry,catalog,authorization,runtimeGuard));
            context.registerBean(ApiExceptionHandler.class,ApiExceptionHandler::new);
            context.registerBean(RequestIdWebFilter.class,RequestIdWebFilter::new);
            context.registerBean(PublicApiKeyWebFilter.class,()->new PublicApiKeyWebFilter(properties,authenticator,new ApiKeyRateLimiter(20,1000)));
            context.registerBean(FixtureControl.class,FixtureControl::new);
            context.refresh();
            server = HttpServer.create().host("127.0.0.1").port(Integer.parseInt(args[0]))
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build())).bindNow();
            System.out.println("AGENT_INTEROP_READY port="+server.port()+" upstream=fixture");
            server.onDispose().block();
        }
    }

    private static Flux<CanonicalEvent> fixture(CanonicalRequest request, MimoProvider provider,
        MimoRequestMapper requestMapper, ObjectMapper mapper) {
        return Flux.defer(() -> {
            if (!List.of("fixture","fixture-codex","fixture-codex-image").contains(request.model())) {
                throw OpenAiRequestException.invalid("model","unknown fixture model route");
            }
            var safe = OpenAiToolBridge.forProvider(new SmartContextWindowManager(mapper).guard(request,null));
            ProviderRequestValidation.requireSupportedRequest(safe,provider.manifest(),provider.protocolContract());
            provider.validate(safe);
            var prepared = requestMapper.prepare(safe);
            if(REQUESTS.size()<1000) REQUESTS.add(Map.of("protocol",request.protocol().name(),
                "model", request.model(),
                "fields",((tools.jackson.databind.node.ObjectNode)request.rawRequest()).propertyNames().stream().sorted().toList(),
                "messages",request.messages().size(),"retained_messages",safe.messages().size(),
                "tool_results",safe.messages().stream().filter(message->"tool".equals(message.path("role").asText())).count(),
                "multimodal_tool_results",safe.messages().stream().filter(message->"tool".equals(message.path("role").asText())
                    && message.path("content").isArray()).count(),
                "image_inputs", safe.messages().stream().flatMap(message -> {
                    var parts = new ArrayList<tools.jackson.databind.JsonNode>();
                    if(message.path("content").isArray()) message.path("content").forEach(parts::add);
                    return parts.stream();
                }).filter(part -> List.of("input_image", "image_url", "image").contains(part.path("type").asText())).count(),
                "tools",prepared.tools().stream().map(tool->tool.name()).toList()));
            var id=request.requestId();
            var events = new ArrayList<CanonicalEvent>();
            var text = safe.messages().stream().map(message->contentText(message.path("content")))
                .collect(java.util.stream.Collectors.joining("\n"));
            if(text.contains("fixture:rate-limit")) return Flux.just(new CanonicalEvent.Failed(1,id,0,"rate_limited","fixture rate limit",Map.of()));
            if(text.contains("fixture:upstream-error") || text.contains("fixture:gateway-timeout")) {
                var status = text.contains("fixture:gateway-timeout") ? 504 : 502;
                return Flux.just(new CanonicalEvent.Failed(1,id,0,"provider_upstream_error",
                    "fixture upstream failure",Map.of("status",status)));
            }
            if(text.contains("fixture:delayed-error")) {
                return Mono.delay(Duration.ofSeconds(5)).map(ignored -> (CanonicalEvent)
                    new CanonicalEvent.Failed(1,id,0,"provider_upstream_error","fixture delayed failure",Map.of("status",504))).flux();
            }
            events.add(new CanonicalEvent.ResponseStarted(1,id,0,"resp_fixture_"+UUID.randomUUID().toString().replace("-","")));
            var last = safe.messages().isEmpty()?null:safe.messages().getLast();
            var hasResult = last!=null && "tool".equals(last.path("role").asText());
            if(hasResult) {
                events.add(new CanonicalEvent.OutputTextDelta(1,id,events.size(),"TOOL_RESULT_OK\n"
                    + ("fixture-codex-image".equals(request.model()) ? "LOCAL_IMAGE_READ_OK" : last.path("content").asText())));
            } else if(!prepared.tools().isEmpty()) {
                var tools = "fixture-codex-image".equals(request.model()) ? prepared.tools().stream()
                    .filter(tool -> "view_image".equals(tool.name())).toList() : prepared.tools();
                var count=text.contains("fixture:parallel")?Math.min(2,prepared.tools().size()):1;
                for(var index=0;index<count;index++) {
                    var tool=tools.get(index);
                    var callId="call_"+UUID.randomUUID().toString().replace("-","");
                    var path = java.util.regex.Pattern.compile("fixture_image_path=([^\\r\\n]+)").matcher(text);
                    var arguments=text.contains("fixture:strict-invalid") ? "{\"a\":\"wrong\",\"b\":30}"
                        : text.contains("fixture:strict-valid") ? "{\"a\":12,\"b\":30}"
                        : "view_image".equals(tool.name()) && path.find()
                        ? mapper.writeValueAsString(mapper.createObjectNode().put("path", path.group(1)))
                        : "exec_command".equals(tool.name())?"{\"cmd\":\"Get-ChildItem -Name\",\"max_output_tokens\":1000,\"yield_time_ms\":1000}"
                        : tool.parameters().path("properties").has("input")?"{\"input\":\"hello fixture\"}":"{}";
                    events.add(new CanonicalEvent.ToolCallStarted(1,id,events.size(),callId,tool.name()));
                    events.add(new CanonicalEvent.ToolArgumentsDelta(1,id,events.size(),callId,arguments));
                    events.add(new CanonicalEvent.ToolCallCompleted(1,id,events.size(),callId,arguments));
                }
            } else {
                events.add(new CanonicalEvent.OutputTextDelta(1,id,events.size(),"SDK_"));
                events.add(new CanonicalEvent.OutputTextDelta(1,id,events.size(),"TEXT_OK"));
            }
            events.add(new CanonicalEvent.Usage(1,id,events.size(),10,5,0));
            events.add(new CanonicalEvent.Completed(1,id,events.size(),text.contains("fixture:length")?"length"
                :!hasResult&&!prepared.tools().isEmpty()?"tool_calls":"stop"));
            return CanonicalEventStream.enforce(safe,Flux.fromIterable(events).delayElements(Duration.ofMillis(25)));
        });
    }

    private static String contentText(tools.jackson.databind.JsonNode content) {
        if (content.isTextual()) return content.asText();
        var text = new ArrayList<String>();
        if (content.isArray()) content.forEach(part -> text.add(part.path("text").asText("")));
        return String.join("\n", text);
    }
}
