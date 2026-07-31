package com.any2api.provider.minmax;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
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
final class MinmaxOfficialProfileRefresher {
    private static final Logger log = LoggerFactory.getLogger(MinmaxOfficialProfileRefresher.class);
    private static final Pattern SCRIPT_SOURCE = Pattern.compile(
        "(?i)<script[^>]+src=[\\\"']([^\\\"']+\\.js(?:\\?[^\\\"']*)?)[\\\"']");
    private static final Pattern VERSION_CODE = Pattern.compile(
        "version_code\\s*:\\s*[\\\"']([0-9]{3,12})[\\\"']");
    private final WebClient webClient;
    private final MinmaxProperties properties;
    private final URI baseUri;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    MinmaxOfficialProfileRefresher(WebClient.Builder builder, MinmaxProperties properties) {
        this.webClient = builder.clone()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
        this.properties = properties;
        this.baseUri = URI.create(properties.getBaseUrl() + "/");
    }

    @Scheduled(
        initialDelayString = "${any2api.provider.minmax.profile-initial-delay:30s}",
        fixedDelayString = "${any2api.provider.minmax.profile-refresh-interval:6h}"
    )
    void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        discover()
            .timeout(Duration.ofMinutes(2))
            .doOnNext(profile -> {
                if (properties.applyOfficialProfile(
                    profile.signatureSalt(), profile.yySalt(), profile.versionCode())) {
                    log.info("Official MinMax request profile refreshed");
                }
            })
            .doOnError(error -> log.warn(
                "Official MinMax request profile refresh failed: {}", error.getMessage()))
            .doFinally(ignored -> refreshing.set(false))
            .onErrorResume(ignored -> Mono.empty())
            .subscribe();
    }

    Mono<ProfilePatch> discover() {
        return webClient.get()
            .uri(baseUri)
            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
            .accept(MediaType.TEXT_HTML)
            .retrieve()
            .bodyToMono(String.class)
            .flatMapMany(this::scripts)
            .flatMap(this::fetchScript, 4)
            .map(MinmaxOfficialProfileRefresher::parseProfile)
            .reduce(ProfilePatch.EMPTY, ProfilePatch::merge)
            .filter(ProfilePatch::hasAny)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "official frontend exposed no recognized request profile")));
    }

    private Flux<URI> scripts(String html) {
        var scripts = new LinkedHashSet<URI>();
        var matcher = SCRIPT_SOURCE.matcher(html);
        while (matcher.find() && scripts.size() < 40) {
            var resolved = baseUri.resolve(matcher.group(1));
            if ("https".equalsIgnoreCase(resolved.getScheme()) && trustedHost(resolved.getHost())) {
                scripts.add(resolved);
            }
        }
        return Flux.fromIterable(scripts);
    }

    private boolean trustedHost(String host) {
        if (host == null) return false;
        if (baseUri.getHost().equalsIgnoreCase(host)) return true;
        for (var configured : properties.getProfileAssetHosts().split(",")) {
            var allowed = configured.trim();
            if (!allowed.isBlank() && allowed.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    private Mono<String> fetchScript(URI script) {
        return webClient.get()
            .uri(script)
            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
            .accept(MediaType.valueOf("application/javascript"), MediaType.TEXT_PLAIN)
            .retrieve()
            .bodyToMono(String.class)
            .onErrorResume(error -> Mono.empty());
    }

    static ProfilePatch parseProfile(String script) {
        var versionMatcher = VERSION_CODE.matcher(script);
        var version = versionMatcher.find() ? versionMatcher.group(1) : null;
        return new ProfilePatch(signatureSalt(script), yySalt(script), version);
    }

    private static String signatureSalt(String script) {
        var offset = 0;
        while (true) {
            var marker = script.indexOf("x-signature", offset);
            if (marker < 0) return null;
            var first = script.indexOf("${", marker);
            var firstEnd = first < 0 ? -1 : script.indexOf('}', first + 2);
            var second = firstEnd < 0 ? -1 : script.indexOf("${", firstEnd + 1);
            if (first >= 0 && first - marker < 300 && firstEnd >= 0 && second >= 0) {
                var candidate = script.substring(firstEnd + 1, second);
                if (validLiteral(candidate, 6, 80)) return candidate;
            }
            offset = marker + 1;
        }
    }

    private static String yySalt(String script) {
        var offset = 0;
        while (true) {
            var marker = script.indexOf("hasSearchParamsPath", offset);
            if (marker < 0) return null;
            var hashCall = script.indexOf("toString())}", marker);
            var end = hashCall < 0 ? -1 : script.indexOf('`', hashCall);
            if (hashCall >= 0 && hashCall - marker < 800 && end > hashCall) {
                var brace = script.indexOf('}', hashCall);
                var candidate = script.substring(brace + 1, end);
                if (validLiteral(candidate, 2, 32)) return candidate;
            }
            offset = marker + 1;
        }
    }

    private static boolean validLiteral(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '$' || character == '{' || character == '}'
                || character == '`' || Character.isWhitespace(character)) return false;
        }
        return true;
    }

    record ProfilePatch(String signatureSalt, String yySalt, String versionCode) {
        private static final ProfilePatch EMPTY = new ProfilePatch(null, null, null);

        ProfilePatch merge(ProfilePatch other) {
            return new ProfilePatch(
                first(signatureSalt, other.signatureSalt),
                first(yySalt, other.yySalt),
                first(versionCode, other.versionCode));
        }

        boolean hasAny() {
            return signatureSalt != null || yySalt != null || versionCode != null;
        }

        private static String first(String current, String candidate) {
            return current == null || current.isBlank() ? candidate : current;
        }
    }
}
