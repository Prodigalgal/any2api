package com.any2api.protocol.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyRequestFeatureDetector;
import com.any2api.config.Any2ApiProperties;
import com.any2api.interop.InteropDatabase;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequestParser;
import com.any2api.protocol.OpenAiResponseWriter;
import com.any2api.routing.ResolvedRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponsesStateIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Any2ApiProperties properties = new Any2ApiProperties();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private InteropDatabase database;
    private GatewayResponseStore store;
    private ResponsesService service;
    private final ApiKeyGrant grant = grant(InteropDatabase.PRIMARY_KEY);

    @BeforeAll void start() throws Exception {
        database = new InteropDatabase();
        store = database.store(mapper);
        service = new ResponsesService(store, new ProviderResponseStateStore(database.jdbc,mapper),
            new CanonicalRequestParser(mapper),new OpenAiResponseWriter(mapper),new ApiKeyAuthorization(),
            new ApiKeyRequestFeatureDetector(),mapper,properties,executor);
    }
    @BeforeEach void clear() { database.jdbc.sql("DELETE FROM gateway_responses").update(); }
    @AfterAll void close() throws Exception { executor.close(); if(database!=null) database.close(); }

    @Test void migrationsStoreJsonAndIsolateOwnersAcrossRepositoryInstances() {
        var input = mapper.createArrayNode().addObject().put("id","one").put("role","user").put("content","hello");
        var body = mapper.createObjectNode().put("id","resp_database").put("status","completed");
        store.save(ResponsesService.owner(grant),grant.keyId(),"mimo","fixture", mapper.createArrayNode().add(input),body,Instant.now().plusSeconds(60),10);
        assertThat(database.store(mapper).find("resp_database",ResponsesService.owner(grant)).orElseThrow().response()).isEqualTo(body);
        assertThat(store.find("resp_database",ResponsesService.owner(grant(InteropDatabase.OTHER_KEY)))).isEmpty();
        assertThat(database.jdbc.sql("SELECT tag FROM databasechangelog WHERE tag IS NOT NULL ORDER BY orderexecuted DESC LIMIT 1")
            .query(String.class).single()).isEqualTo("0.24.0");
    }

    @Test void completesStoredToolLoopWithPaginationDeletionAndExpiry() {
        var raw = mapper.createObjectNode().put("input","inspect").put("store",true);
        raw.putArray("tools").addObject().put("type","function").put("name","inspect");
        var prepared = service.prepare(raw,new ResolvedRoute("mimo","fixture"),grant,"state-test").block();
        var first = service.track(prepared.responseRequest(),grant,Flux.just(
            new CanonicalEvent.ResponseStarted(1,"state-test",0,"upstream"),
            new CanonicalEvent.ToolCallStarted(1,"state-test",1,"one","inspect"),
            new CanonicalEvent.ToolCallCompleted(1,"state-test",2,"one","{}"),
            new CanonicalEvent.Completed(1,"state-test",3,"tool_calls"))).collectList().block();
        var id = ((CanonicalEvent.ResponseStarted) first.getFirst()).responseId();
        assertThat(service.storedContinuationRoute(mapper.createObjectNode().put("previous_response_id", id), grant).block())
            .isEqualTo(new ResolvedRoute("mimo", "fixture"));
        var follow = mapper.createObjectNode().put("previous_response_id",id).put("instructions","new rules");
        follow.putArray("input").addObject().put("type","function_call_output").put("call_id","one").put("output","found");
        var next = service.prepare(follow,new ResolvedRoute("mimo","fixture"),grant,"next-test").block();
        assertThat(next.inferenceRequest().rawRequest().has("previous_response_id")).isFalse();
        assertThat(next.inferenceRequest().messages().stream().map(message->message.path("role").asText()))
            .containsExactly("system","user","assistant","tool");
        var page = service.listInput(id,null,grant,1,"asc",null,null).block();
        assertThat(page.path("data").size()).isEqualTo(1);
        assertThat(service.listInput(id,null,grant,1,"asc",page.path("last_id").asText(),null).block().path("data").isEmpty()).isTrue();
        assertThatThrownBy(() -> service.retrieve(id,null,grant(InteropDatabase.OTHER_KEY)).block()).isInstanceOf(ResponseNotFoundException.class);
        assertThat(service.delete(id,null,grant).block().path("deleted").asBoolean()).isTrue();
        assertThatThrownBy(() -> service.retrieve(id,null,grant).block()).isInstanceOf(ResponseNotFoundException.class);
        var expired = mapper.createObjectNode().put("id","expired").put("status","completed");
        store.save(ResponsesService.owner(grant),grant.keyId(),"mimo","fixture",mapper.createArrayNode(),expired,Instant.now().minusSeconds(1),10);
        assertThat(store.find("expired",ResponsesService.owner(grant))).isEmpty();
    }

    @Test void appliesConcurrentStorageQuotaAndKeepsFinalStateImmutable() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for(var i=0;i<2;i++) calls.add(executor.submit(() -> {
            try {
                barrier.await();
                store.save(ResponsesService.owner(grant),grant.keyId(),"mimo","fixture",mapper.createArrayNode(),
                    mapper.createObjectNode().put("id",UUID.randomUUID().toString()).put("status","in_progress"),Instant.now().plusSeconds(60),1);
                successes.incrementAndGet();
            } catch(ResponseStorageLimitException error) { rejected.incrementAndGet(); }
            catch(Exception error) { throw new RuntimeException(error); }
        }));
        for(var future:calls) future.get();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        var id=database.jdbc.sql("SELECT response_id FROM gateway_responses").query(String.class).single();
        for(var status:java.util.List.of("completed","in_progress")) store.save(ResponsesService.owner(grant),grant.keyId(),"mimo","fixture",mapper.createArrayNode(),
            mapper.createObjectNode().put("id",id).put("status",status),Instant.now().plusSeconds(60),1);
        assertThat(store.find(id,ResponsesService.owner(grant)).orElseThrow().response().path("status").asText()).isEqualTo("completed");
    }

    @Test void persistsCancellationAfterResponseStarted() throws Exception {
        var raw=mapper.createObjectNode().put("store",true).put("stream",true).put("input","hello");
        var request=service.prepare(raw,new ResolvedRoute("mimo","fixture"),grant,"cancel-test").block().responseRequest();
        var responseId=new java.util.concurrent.atomic.AtomicReference<String>();
        StepVerifier.create(service.track(request,grant,Flux.concat(Flux.just(
            new CanonicalEvent.ResponseStarted(1,"cancel-test",0,"original")),Flux.never())))
            .assertNext(event->responseId.set(((CanonicalEvent.ResponseStarted)event).responseId())).thenCancel().verify();
        var deadline=Instant.now().plusSeconds(3);
        while(Instant.now().isBefore(deadline) && !"cancelled".equals(store.find(responseId.get(),ResponsesService.owner(grant)).orElseThrow().response().path("status").asText())) {
            Thread.sleep(20);
        }
        assertThat(service.retrieve(responseId.get(),null,grant).block().path("status").asText()).isEqualTo("cancelled");
    }

    @Test void deletionDuringGenerationCannotBeRecreatedByCompletion() {
        var request = service.prepare(mapper.createObjectNode().put("store", true).put("input", "hello"),
            new ResolvedRoute("mimo", "fixture"), grant, "delete-race").block().responseRequest();
        var source = reactor.core.publisher.Sinks.many().unicast().<CanonicalEvent>onBackpressureBuffer();
        var id = new java.util.concurrent.atomic.AtomicReference<String>();
        StepVerifier.create(service.track(request, grant, source.asFlux()))
            .then(() -> source.tryEmitNext(new CanonicalEvent.ResponseStarted(1, "delete-race", 0, "upstream")))
            .assertNext(event -> {
                id.set(((CanonicalEvent.ResponseStarted) event).responseId());
                service.delete(id.get(), null, grant).block();
            })
            .then(() -> {
                source.tryEmitNext(new CanonicalEvent.OutputTextDelta(1, "delete-race", 1, "answer"));
                source.tryEmitNext(new CanonicalEvent.Completed(1, "delete-race", 2, "stop"));
                source.tryEmitComplete();
            }).expectNextCount(2).verifyComplete();
        assertThatThrownBy(() -> service.retrieve(id.get(), null, grant).block()).isInstanceOf(ResponseNotFoundException.class);
    }

    @Test void rejectsStateSizeLimitAndNativeCrossKeyContinuation() {
        properties.getResponses().setMaxStateBytes(8);
        try {
            var request=service.prepare(mapper.createObjectNode().put("store",true).put("input","large"),new ResolvedRoute("mimo","fixture"),grant,"size-test").block().responseRequest();
            assertThatThrownBy(()->service.track(request,grant,Flux.just(new CanonicalEvent.ResponseStarted(1,"size-test",0,"upstream"))).blockLast())
                .hasMessageContaining("size limit");
        } finally { properties.getResponses().setMaxStateBytes(2<<20); }
        assertThatThrownBy(()->service.prepare(mapper.createObjectNode().put("previous_response_id","unknown"),
            new ResolvedRoute("grok_web","grok-3"),grant,"legacy-test").block()).isInstanceOf(ResponseNotFoundException.class);
        var accountId = UUID.randomUUID();
        database.jdbc.sql("INSERT INTO accounts(id,provider_id,external_id,status) VALUES(:id,'grok_web',:external,'ACTIVE')")
            .param("id", accountId).param("external", accountId.toString()).update();
        var nativeStore = new ProviderResponseStateStore(database.jdbc, mapper);
        nativeStore.save("native-owned", "grok_web", accountId, mapper.createObjectNode().put("model", "grok-3"), Duration.ofMinutes(5), grant.keyId());
        var continuation = mapper.createObjectNode().put("previous_response_id", "native-owned").put("input", "continue");
        assertThatThrownBy(() -> service.prepare(continuation, new ResolvedRoute("grok_web", "grok-3"),
            grant(InteropDatabase.OTHER_KEY), "cross-native-test").block()).isInstanceOf(ResponseNotFoundException.class);
        assertThat(service.prepare(continuation, new ResolvedRoute("grok_web", "grok-3"), grant, "owned-native-test").block()
            .inferenceRequest().rawRequest().path("previous_response_id").asText()).isEqualTo("native-owned");
        assertThatThrownBy(() -> service.prepare(continuation, new ResolvedRoute("grok_web", "grok-4"),
            grant, "model-native-test").block()).hasMessageContaining("original model");
    }

    @Test void finalizesStoredErrorsAndRechecksRevokedPermissions() {
        properties.getResponses().setMaxStateBytes(1800);
        try {
            var request = service.prepare(mapper.createObjectNode().put("store", true).put("input", "hello"),
                new ResolvedRoute("mimo", "fixture"), grant, "error-test").block().responseRequest();
            var id = new java.util.concurrent.atomic.AtomicReference<String>();
            assertThatThrownBy(() -> service.track(request, grant, Flux.just(
                new CanonicalEvent.ResponseStarted(1, "error-test", 0, "upstream"),
                new CanonicalEvent.OutputTextDelta(1, "error-test", 1, "large".repeat(600))))
                .doOnNext(event -> { if (event instanceof CanonicalEvent.ResponseStarted start) id.set(start.responseId()); })
                .blockLast()).hasMessageContaining("size limit");
            assertThat(service.retrieve(id.get(), null, grant).block().path("status").asText()).isEqualTo("failed");
        } finally { properties.getResponses().setMaxStateBytes(2 << 20); }
        var input = mapper.createArrayNode().addObject().put("type", "function_call_output").put("call_id", "one").put("output", "result");
        var body = mapper.createObjectNode().put("id", "resp_permission").put("status", "completed");
        store.save(ResponsesService.owner(grant), grant.keyId(), "mimo", "fixture", mapper.createArrayNode().add(input), body,
            Instant.now().plusSeconds(60), 10);
        var revoked = new ApiKeyGrant(grant.keyId(), "revoked-tools", Map.of("mimo", com.any2api.auth.ApiKeyProviderScope.allModels("mimo")),
            Set.of(com.any2api.auth.ApiKeyProtocol.RESPONSES), Set.of(), null, false);
        assertThatThrownBy(() -> service.retrieve("resp_permission", null, revoked).block())
            .isInstanceOf(com.any2api.auth.ApiKeyScopeException.class);
        assertThatThrownBy(() -> service.prepare(mapper.createObjectNode().put("previous_response_id", "resp_permission"),
            new ResolvedRoute("mimo", "fixture"), revoked, "revoked-test").block())
            .isInstanceOf(com.any2api.auth.ApiKeyScopeException.class);
        assertThatThrownBy(() -> service.retrieve("resp_permission", "longcat", grant).block())
            .isInstanceOf(ResponseNotFoundException.class);
    }

    @Test void migrationRollbackPreservesExistingProviderSchema() throws Exception {
        var providerCount=database.jdbc.sql("SELECT COUNT(*) FROM providers").query(Long.class).single();
        try(var migration=database.migration()) { migration.rollback(2,new liquibase.Contexts(),new liquibase.LabelExpression()); }
        assertThat(database.jdbc.sql("SELECT to_regclass('gateway_responses')::text").query(String.class).optional()).isEmpty();
        assertThat(database.jdbc.sql("SELECT COUNT(*) FROM providers").query(Long.class).single()).isEqualTo(providerCount);
        try(var migration=database.migration()) { migration.update(new liquibase.Contexts(),new liquibase.LabelExpression()); }
    }

    public static ApiKeyGrant grant(UUID id) { return new ApiKeyGrant(id,"fixture",Map.of(),Set.of(),Set.of(),null,true); }
}
