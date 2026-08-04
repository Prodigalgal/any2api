package com.any2api.protocol;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public final class UsageNormalizer {
    private static final long PLAUSIBILITY_RATIO = 4;
    private static final long PLAUSIBILITY_SLACK = 128;

    public Flux<CanonicalEvent> normalize(CanonicalRequest request, Flux<CanonicalEvent> events) {
        return Flux.defer(() -> {
            var outputBytes = new AtomicLong();
            var upstreamUsage = new AtomicReference<CanonicalEvent.Usage>();
            return events.concatMap(event -> {
                if (event instanceof CanonicalEvent.OutputTextDelta text) {
                    outputBytes.addAndGet(bytes(text.delta()));
                } else if (event instanceof CanonicalEvent.ReasoningDelta reasoning) {
                    outputBytes.addAndGet(bytes(reasoning.delta()));
                } else if (event instanceof CanonicalEvent.ToolArgumentsDelta arguments) {
                    outputBytes.addAndGet(bytes(arguments.delta()));
                }
                if (event instanceof CanonicalEvent.Usage usage) {
                    upstreamUsage.set(usage);
                    return Flux.empty();
                }
                if (event instanceof CanonicalEvent.Completed completed) {
                    var reported = upstreamUsage.get();
                    var estimatedInput = estimate(bytes(request.rawRequest().toString()));
                    var estimatedOutput = outputBytes.get() == 0 ? 0 : estimate(outputBytes.get());
                    var inputTrusted = reported != null && reported.inputTokens() > 0
                        && plausible(reported.inputTokens(), estimatedInput);
                    var outputTrusted = reported != null && reported.outputTokens() > 0
                        && plausible(reported.outputTokens(), estimatedOutput);
                    var completeUpstream = reported != null
                        && reported.source() == UsageSource.UPSTREAM
                        && inputTrusted
                        && (outputTrusted || outputBytes.get() == 0);
                    var normalized = new CanonicalEvent.Usage(
                        completed.schemaVersion(), completed.requestId(), completed.sequenceNumber(),
                        inputTrusted
                            ? reported.inputTokens() : estimatedInput,
                        outputTrusted
                            ? reported.outputTokens() : estimatedOutput,
                        reported == null || !inputTrusted ? 0
                            : Math.min(reported.inputTokens(),
                                Math.max(0, reported.cacheReadTokens())),
                        completeUpstream ? UsageSource.UPSTREAM : UsageSource.ESTIMATED,
                        reported == null ? 0 : reported.rawInputTokens(),
                        reported == null ? 0 : reported.rawOutputTokens(),
                        reported == null ? 0 : reported.rawCacheReadTokens());
                    var terminal = new CanonicalEvent.Completed(
                        completed.schemaVersion(), completed.requestId(),
                        completed.sequenceNumber() + 1, completed.finishReason());
                    return Flux.just(normalized, terminal);
                }
                return Flux.just(event);
            });
        });
    }

    private long bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private long estimate(long bytes) {
        return Math.max(1, (bytes + 3) / 4);
    }

    private boolean plausible(long reported, long estimated) {
        var ceiling = estimated > (Long.MAX_VALUE - PLAUSIBILITY_SLACK) / PLAUSIBILITY_RATIO
            ? Long.MAX_VALUE : estimated * PLAUSIBILITY_RATIO + PLAUSIBILITY_SLACK;
        return reported <= ceiling;
    }
}
