package com.any2api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.protocol.UsageSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class PromptExactCacheManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    private final PromptExactCacheManager cacheManager = new PromptExactCacheManager(
        beanFactory.getBeanProvider(org.springframework.data.redis.core.ReactiveStringRedisTemplate.class),
        mapper
    );

    @Test
    void cachesAndRetrievesNonStreamingResponse() {
        var raw = mapper.createObjectNode();
        raw.putArray("messages").addObject().put("role", "user").put("content", "What is 2+2?");
        var request = new CanonicalRequest(
            "req-1",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "qwen",
            "qwen-max",
            false,
            List.of(raw.path("messages").get(0)),
            Map.of("temperature", 0.7),
            Map.of(),
            List.of(),
            Map.of(),
            raw);

        var events = List.<CanonicalEvent>of(
            new CanonicalEvent.OutputTextDelta(1, "req-1", 1, "4"),
            new CanonicalEvent.Usage(1, "req-1", 2, 10, 5, 0, UsageSource.UPSTREAM),
            new CanonicalEvent.Completed(1, "req-1", 3, "stop")
        );

        // Initially empty
        var initial = cacheManager.get(request).block();
        assertThat(initial).isNull();

        // Store
        cacheManager.put(request, events).block();

        // Read back
        var cached = cacheManager.get(request).block();
        assertThat(cached).isNotNull();
        assertThat(cached).hasSize(4);
        assertThat(cached.get(0)).isInstanceOf(CanonicalEvent.ResponseStarted.class);

        var content = (CanonicalEvent.OutputTextDelta) cached.get(1);
        assertThat(content.delta()).isEqualTo("4");

        var usage = (CanonicalEvent.Usage) cached.get(2);
        assertThat(usage.outputTokens()).isEqualTo(5);
        assertThat(usage.cacheReadTokens()).isEqualTo(10);
    }

    @Test
    void ignoresStreamingRequests() {
        var raw = mapper.createObjectNode();
        raw.put("stream", true);
        var request = new CanonicalRequest(
            "req-2",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "qwen",
            "qwen-max",
            true,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            raw);

        assertThat(cacheManager.isEligibleForCache(request)).isFalse();
        assertThat(cacheManager.get(request).block()).isNull();
    }

    @Test
    void doesNotCacheFailedResponses() {
        var raw = mapper.createObjectNode();
        var request = new CanonicalRequest(
            "req-3",
            CanonicalRequest.Protocol.CHAT_COMPLETIONS,
            "qwen",
            "qwen-max",
            false,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            raw);

        var failedEvents = List.<CanonicalEvent>of(
            new CanonicalEvent.Failed(1, "req-3", 1, "rate_limit", "429 Too Many Requests", Map.of())
        );

        cacheManager.put(request, failedEvents).block();
        assertThat(cacheManager.get(request).block()).isNull();
    }

    @Test
    void isolatesApiKeysAndRehydratesFreshResponseIdentifiers() {
        var request = request("{\"input\":\"hello\"}");
        var owner = java.util.UUID.randomUUID();
        cacheManager.put(request, owner, List.of(new CanonicalEvent.OutputTextDelta(1, request.requestId(), 1, "hello"),
            new CanonicalEvent.Completed(1, request.requestId(), 2, "stop"))).block();
        assertThat(cacheManager.get(request, java.util.UUID.randomUUID()).block()).isNull();
        var first = (CanonicalEvent.ResponseStarted) cacheManager.get(request, owner).block().getFirst();
        var next = (CanonicalEvent.ResponseStarted) cacheManager.get(request, owner).block().getFirst();
        assertThat(first.responseId()).isNotEqualTo(next.responseId());
        assertThat(first.sequenceNumber()).isZero();
    }

    @Test
    void excludesToolHistoryAndResponsesWithNonTextEvents() {
        assertThat(cacheManager.isEligibleForCache(request("""
            {"input":[{"type":"function_call","name":"inspect","call_id":"one","arguments":"{}"},
              {"type":"function_call_output","call_id":"one","output":"result"}]}
            """))).isFalse();
        assertThat(cacheManager.isEligibleForCache(request("{\"input\":\"hello\",\"store\":true}"))).isFalse();
        var request = request("{\"input\":\"hello\"}");
        cacheManager.put(request, List.of(new CanonicalEvent.ReasoningDelta(1, request.requestId(), 1, "reason"),
            new CanonicalEvent.Completed(1, request.requestId(), 2, "stop"))).block();
        assertThat(cacheManager.get(request).block()).isNull();
        cacheManager.put(request, List.of(new CanonicalEvent.OutputTextDelta(1, request.requestId(), 1, "partial"),
            new CanonicalEvent.Completed(1, request.requestId(), 2, "length"))).block();
        assertThat(cacheManager.get(request).block()).isNull();
    }

    private CanonicalRequest request(String json) {
        return new com.any2api.protocol.CanonicalRequestParser(mapper).parse(CanonicalRequest.Protocol.RESPONSES,
            new com.any2api.routing.ResolvedRoute("mimo", "fixture"),
            (tools.jackson.databind.node.ObjectNode) mapper.readTree(json));
    }
}
