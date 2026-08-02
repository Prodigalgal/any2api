package com.any2api.provider.deepseek;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
final class DeepseekOfficialProfileRefresher {
    private static final Logger log = LoggerFactory.getLogger(
        DeepseekOfficialProfileRefresher.class);
    private static final Pattern SCRIPT_SOURCE = Pattern.compile(
        "(?i)<script[^>]+src=[\\\"']([^\\\"']+\\.js(?:\\?[^\\\"']*)?)[\\\"']");
    private static final Pattern APP_VERSION = Pattern.compile(
        "appVersion\\s*:\\s*[\\\"']([0-9]+(?:\\.[0-9]+){1,3})[\\\"']");

    private final WebClient client;
    private final DeepseekProperties properties;
    private final URI baseUri;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    DeepseekOfficialProfileRefresher(
        WebClient.Builder builder,
        DeepseekProperties properties
    ) {
        client = builder.clone()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
            .build();
        this.properties = properties;
        baseUri = URI.create(properties.getBaseUrl() + "/");
    }

    @Scheduled(
        initialDelayString = "${any2api.provider.deepseek.profile-initial-delay:30s}",
        fixedDelayString = "${any2api.provider.deepseek.profile-refresh-interval:6h}"
    )
    void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        discover()
            .timeout(Duration.ofMinutes(2))
            .doOnNext(version -> {
                if (properties.applyOfficialVersion(version)) {
                    log.info("Official DeepSeek client profile refreshed");
                }
            })
            .doOnError(error -> log.warn(
                "Official DeepSeek client profile refresh failed: {}", error.getMessage()))
            .doFinally(ignored -> refreshing.set(false))
            .onErrorResume(ignored -> Mono.empty())
            .subscribe();
    }

    Mono<String> discover() {
        return client.get().uri(baseUri.resolve("sign_in"))
            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
            .accept(MediaType.TEXT_HTML)
            .retrieve().bodyToMono(String.class)
            .flatMapMany(this::scripts)
            .flatMap(this::fetchScript, 4)
            .map(DeepseekOfficialProfileRefresher::parseVersion)
            .filter(value -> !value.isBlank())
            .next()
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "official frontend exposed no DeepSeek client version")));
    }

    static String parseVersion(String script) {
        var matcher = APP_VERSION.matcher(script == null ? "" : script);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Flux<URI> scripts(String html) {
        var result = new LinkedHashSet<URI>();
        var matcher = SCRIPT_SOURCE.matcher(html == null ? "" : html);
        while (matcher.find() && result.size() < 40) {
            var uri = baseUri.resolve(matcher.group(1));
            if ("https".equalsIgnoreCase(uri.getScheme()) && trusted(uri.getHost())) {
                result.add(uri);
            }
        }
        return Flux.fromIterable(result);
    }

    private boolean trusted(String host) {
        return host != null && (host.equalsIgnoreCase(baseUri.getHost())
            || host.equalsIgnoreCase(properties.getAssetHost()));
    }

    private Mono<String> fetchScript(URI uri) {
        return client.get().uri(uri)
            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
            .accept(MediaType.valueOf("application/javascript"), MediaType.TEXT_PLAIN)
            .retrieve().bodyToMono(String.class)
            .onErrorResume(ignored -> Mono.empty());
    }
}
