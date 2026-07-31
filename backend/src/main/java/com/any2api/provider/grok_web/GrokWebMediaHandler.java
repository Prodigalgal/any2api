package com.any2api.provider.grok_web;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.media.GeneratedMedia;
import com.any2api.media.MediaOperation;
import com.any2api.media.MediaRequest;
import com.any2api.media.MediaResult;
import com.any2api.media.ProviderMediaHandler;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderFailure;
import com.any2api.proxy.ProxyPoolService;
import com.any2api.proxy.ProxyTrafficScope;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class GrokWebMediaHandler implements ProviderMediaHandler {
    private final GrokWebProtocolClient protocol;
    private final ProxyPoolService proxyPools;
    private final GrokWebFailureClassifier failures;

    public GrokWebMediaHandler(
        GrokWebProtocolClient protocol,
        ProxyPoolService proxyPools,
        GrokWebFailureClassifier failures
    ) {
        this.protocol = protocol;
        this.proxyPools = proxyPools;
        this.failures = failures;
    }

    @Override public String providerId() { return "grok_web"; }

    @Override
    public boolean supports(MediaRequest request) {
        try {
            var model = GrokWebModelCatalog.require(request.model());
            return switch (request.operation()) {
                case IMAGE_GENERATION -> model.kind() == GrokWebModelCatalog.Kind.IMAGE
                    && Set.of("imagine-lite", "imagine").contains(model.protocolModel());
                case IMAGE_EDITING -> model.kind() == GrokWebModelCatalog.Kind.IMAGE_EDIT;
                default -> false;
            };
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public void validate(MediaRequest request) {
        var model = GrokWebModelCatalog.require(request.model());
        if (request.operation() == MediaOperation.IMAGE_EDITING) {
            if (request.count() != 1) {
                throw new IllegalArgumentException("Grok Web image editing supports only n=1");
            }
            if (request.inputs().isEmpty() || request.inputs().size() > 8) {
                throw new IllegalArgumentException(
                    "Grok Web image editing requires between 1 and 8 images");
            }
            requireOptions(request, Set.of("aspect_ratio", "size", "resolution"));
            var resolution = text(request, "resolution").toLowerCase();
            if (!resolution.isBlank() && !"1k".equals(resolution)) {
                throw new IllegalArgumentException(
                    "Grok Web image editing supports only resolution=1k");
            }
            editAspectRatio(text(request, "aspect_ratio"), text(request, "size"));
            return;
        }
        if ("imagine-lite".equals(model.protocolModel())) {
            requireOptions(request, Set.of());
            return;
        }
        requireOptions(request, Set.of("aspect_ratio", "size", "resolution"));
        aspectRatio(text(request, "aspect_ratio"), text(request, "size"));
        var resolution = text(request, "resolution").toLowerCase();
        if (!resolution.isBlank() && !Set.of("1k", "2k").contains(resolution)) {
            throw new IllegalArgumentException("resolution must be 1k or 2k");
        }
    }

    @Override
    public boolean supportsAccount(MediaRequest request, ProviderAccountProfile account) {
        var model = GrokWebModelCatalog.require(request.model());
        return GrokWebModelCatalog.supports(
            String.valueOf(account.metadata().getOrDefault("tier", "basic")), model);
    }

    @Override
    public Mono<MediaResult> generate(
        MediaRequest request,
        LeasedProviderAccount account
    ) {
        var model = GrokWebModelCatalog.require(request.model());
        var credentialPatch = new AtomicReference<tools.jackson.databind.JsonNode>(
            tools.jackson.databind.node.MissingNode.getInstance());
        if (request.operation() == MediaOperation.IMAGE_EDITING) {
            var ratio = editAspectRatio(
                text(request, "aspect_ratio"), text(request, "size"));
            return protocol.editImage(
                    account.credential(), proxyPool(), affinity(account.metadata()),
                    request.prompt(), request.inputs(), ratio, credentialPatch::set)
                .map(image -> new MediaResult(java.util.List.of(new GeneratedMedia(
                    image.contentType(), image.content(), request.prompt())),
                    credentialPatch.get()));
        }
        if ("imagine-lite".equals(model.protocolModel())) {
            return map(protocol.generateLiteImages(
                account.credential(), proxyPool(), affinity(account.metadata()),
                request.prompt(), request.count(), credentialPatch::set),
                request.prompt(), credentialPatch);
        }
        var ratio = aspectRatio(text(request, "aspect_ratio"), text(request, "size"));
        var resolution = text(request, "resolution").toLowerCase();
        if (resolution.isBlank()) resolution = "1k";
        return map(protocol.generateImagineImages(
            account.credential(), proxyPool(), affinity(account.metadata()),
            request.prompt(), request.count(), ratio, resolution, credentialPatch::set),
            request.prompt(), credentialPatch);
    }

    private void requireOptions(MediaRequest request, Set<String> supported) {
        var unsupported = request.options().keySet().stream()
            .filter(option -> !supported.contains(option))
            .sorted().toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                "Grok Web image generation does not support: "
                    + String.join(", ", unsupported));
        }
    }

    private Mono<MediaResult> map(
        Mono<java.util.List<GrokWebProtocolClient.DownloadedImage>> source,
        String prompt,
        AtomicReference<tools.jackson.databind.JsonNode> credentialPatch
    ) {
        return source
            .map(images -> new MediaResult(images.stream()
                .map(image -> new GeneratedMedia(
                    image.contentType(), image.content(), prompt))
                .toList(), credentialPatch.get()));
    }

    private Map<String, Object> proxyPool() {
        return proxyPools.runtimeForProvider(
            providerId(), ProxyTrafficScope.INFERENCE).orElse(Map.of());
    }

    private String text(MediaRequest request, String name) {
        var value = request.options().get(name);
        if (value instanceof tools.jackson.databind.JsonNode node) return node.asText("").trim();
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String aspectRatio(String explicit, String size) {
        var value = explicit.isBlank() ? size : explicit;
        if (value.isBlank()) return "auto";
        var normalized = value.toLowerCase();
        var resolved = Map.ofEntries(
            Map.entry("auto", "auto"), Map.entry("1:1", "1:1"),
            Map.entry("16:9", "16:9"), Map.entry("9:16", "9:16"),
            Map.entry("4:3", "4:3"), Map.entry("3:4", "3:4"),
            Map.entry("3:2", "3:2"), Map.entry("2:3", "2:3"),
            Map.entry("2:1", "2:1"), Map.entry("1:2", "1:2"),
            Map.entry("19.5:9", "19.5:9"), Map.entry("9:19.5", "9:19.5"),
            Map.entry("20:9", "20:9"), Map.entry("9:20", "9:20"),
            Map.entry("1280x720", "16:9"), Map.entry("720x1280", "9:16"),
            Map.entry("1792x1024", "3:2"), Map.entry("1536x1024", "3:2"),
            Map.entry("1024x1792", "2:3"), Map.entry("1024x1536", "2:3"),
            Map.entry("1024x1024", "1:1")
        ).get(normalized);
        if (resolved == null) throw new IllegalArgumentException("aspect_ratio is unsupported");
        return resolved;
    }

    private String editAspectRatio(String explicit, String size) {
        if (explicit.isBlank() && size.isBlank()) return "";
        return aspectRatio(explicit, size);
    }

    @Override
    public ProviderFailure classify(Throwable error) { return failures.classify(error); }

    private String affinity(Map<String, Object> metadata) {
        return String.valueOf(metadata.getOrDefault("identity_group_id", "")).trim();
    }
}
