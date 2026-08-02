package com.any2api.provider.deepseek;

import com.any2api.transport.BrowserTransportClient;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    private final BrowserTransportClient transport;
    private final DeepseekProperties properties;
    private final URI baseUri;
    private final URI assetOrigin;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    DeepseekOfficialProfileRefresher(
        BrowserTransportClient transport,
        DeepseekProperties properties
    ) {
        this.transport = transport;
        this.properties = properties;
        baseUri = URI.create(properties.getBaseUrl() + "/");
        assetOrigin = URI.create("https://" + properties.getAssetHost());
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
        return Mono.usingWhen(
            transport.open(openCommand()),
            session -> fetchIndex(session)
                .flatMapMany(this::scripts)
                .flatMap(uri -> fetchScript(session, uri), 4)
                .map(DeepseekOfficialProfileRefresher::parseVersion)
                .filter(value -> !value.isBlank())
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "official frontend exposed no DeepSeek client version"))),
            this::close,
            (session, ignored) -> close(session),
            this::close);
    }

    static String parseVersion(String script) {
        var matcher = APP_VERSION.matcher(script == null ? "" : script);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Mono<String> fetchIndex(BrowserTransportClient.Session session) {
        return fetchIndexResponse(session).flatMap(response -> {
            if (response.successful()) return Mono.just(response.text());
            if (response.status() != 202 && response.status() != 403) {
                return Mono.error(new IllegalStateException(
                    "official frontend returned HTTP " + response.status()));
            }
            return transport.refreshClearance(session.id(), "/sign_in")
                .then(fetchIndexResponse(session))
                .flatMap(retried -> retried.successful()
                    ? Mono.just(retried.text())
                    : Mono.error(new IllegalStateException(
                        "official frontend returned HTTP " + retried.status()
                            + " after browser clearance")));
        });
    }

    private Mono<BrowserTransportClient.BufferedResponse> fetchIndexResponse(
        BrowserTransportClient.Session session
    ) {
        var request = new BrowserTransportClient.Request(
            "GET", "/sign_in", Map.of(),
            BrowserTransportClient.FingerprintProfile.NAVIGATION,
            null, 90);
        return transport.request(session.id(), request);
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

    private Mono<String> fetchScript(
        BrowserTransportClient.Session session,
        URI uri
    ) {
        var origin = origin(uri);
        var path = uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        var request = new BrowserTransportClient.Request(
            "GET", path, Map.of("Accept", "application/javascript, text/javascript, */*"),
            BrowserTransportClient.FingerprintProfile.NONE,
            null, 90, origin.equals(baseUri.resolve("/")) ? null : origin);
        return transport.request(session.id(), request)
            .filter(BrowserTransportClient.BufferedResponse::successful)
            .map(BrowserTransportClient.BufferedResponse::text)
            .onErrorResume(ignored -> Mono.empty());
    }

    private BrowserTransportClient.OpenCommand openCommand() {
        return new BrowserTransportClient.OpenCommand(
            baseUri.resolve("/"), Map.of(), List.of("." + baseUri.getHost()),
            properties.getUserAgent(), properties.getBrowserProfile(), "v2",
            Map.of(), 180, List.of(assetOrigin), "deepseek-official-profile",
            false, "", "");
    }

    private Mono<Void> close(BrowserTransportClient.Session session) {
        return transport.close(session.id()).then();
    }

    private static URI origin(URI uri) {
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }
}
