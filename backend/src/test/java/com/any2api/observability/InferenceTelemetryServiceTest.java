package com.any2api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.protocol.CanonicalEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.ObjectMapper;

class InferenceTelemetryServiceTest {
    @Test
    void persistsDistinctEventTypesForReasoningAndAnswerDeltas() {
        var mapper = new ObjectMapper();
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        var executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());
        var meters = new SimpleMeterRegistry();
        try {
            var service = new InferenceTelemetryService(jdbc, meters, executor, mapper);
            var trace = new InferenceTelemetryService.InferenceTrace("typed-snapshot", "test", "model",
                "CHAT_COMPLETIONS", UUID.randomUUID());
            var started = service.start(trace, 1);
            started.record(new CanonicalEvent.ReasoningDelta(1, trace.requestId(), 1, "plan"));
            started.record(new CanonicalEvent.OutputTextDelta(1, trace.requestId(), 2, "answer"));
            started.record(new CanonicalEvent.Completed(1, trace.requestId(), 3, "stop"));
            started.terminal();
            started.finish(SignalType.ON_COMPLETE);

            var captured = ArgumentCaptor.forClass(Object.class);
            verify(statement).param(eq("outputSnapshot"), captured.capture());
            var events = mapper.readTree(captured.getValue().toString()).path("events");
            assertThat(events.get(0).path("eventType").asText()).isEqualTo("ReasoningDelta");
            assertThat(events.get(1).path("eventType").asText()).isEqualTo("OutputTextDelta");
            assertThat(events.get(1).path("delta").asText()).isEqualTo("answer");
            assertThat(events.get(2).path("eventType").asText()).isEqualTo("Completed");
        } finally {
            meters.close();
        }
    }
}
