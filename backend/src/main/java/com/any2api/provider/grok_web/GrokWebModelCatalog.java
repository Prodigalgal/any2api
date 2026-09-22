package com.any2api.provider.grok_web;

import java.util.List;
import java.util.Map;

final class GrokWebModelCatalog {
    enum Tier { BASIC, SUPER, HEAVY }

    private static final List<ModelSpec> MODELS = List.of(
        new ModelSpec("grok-chat-fast", "fast", "", Tier.BASIC, Kind.CHAT),
        new ModelSpec("grok-chat-auto", "auto", "", Tier.SUPER, Kind.CHAT),
        new ModelSpec("grok-chat-expert", "expert", "", Tier.SUPER, Kind.CHAT),
        new ModelSpec("grok-chat-heavy", "heavy", "", Tier.HEAVY, Kind.CHAT),
        new ModelSpec("grok-imagine-image", "fast", "imagine-lite", Tier.BASIC, Kind.IMAGE),
        new ModelSpec("grok-imagine-image-quality", "", "imagine", Tier.SUPER, Kind.IMAGE),
        new ModelSpec("grok-imagine-image-edit", "", "imagine-image-edit", Tier.SUPER, Kind.IMAGE_EDIT),
        new ModelSpec("grok-imagine-video", "", "imagine-video-gen", Tier.SUPER, Kind.VIDEO)
    );
    private static final Map<String, ModelSpec> BY_ID = MODELS.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(ModelSpec::id, value -> value));

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("grok-3", "grok-chat-fast"),
        Map.entry("grok-3-mini", "grok-chat-fast"),
        Map.entry("grok-3-fast", "grok-chat-fast"),
        Map.entry("grok-2", "grok-chat-fast"),
        Map.entry("grok-2-mini", "grok-chat-fast"),
        Map.entry("grok-beta", "grok-chat-fast"),
        Map.entry("grok-3-deepsearch", "grok-chat-heavy"),
        Map.entry("grok-3-reasoning", "grok-chat-heavy"),
        Map.entry("grok-3-expert", "grok-chat-expert")
    );

    private GrokWebModelCatalog() {}

    static List<String> modelIds() {
        var ids = new java.util.ArrayList<>(MODELS.stream().map(ModelSpec::id).toList());
        ids.addAll(List.of("grok-3", "grok-3-mini", "grok-2", "grok-beta", "grok-3-deepsearch"));
        return List.copyOf(ids);
    }

    static ModelSpec require(String id) {
        if (id == null) throw new IllegalArgumentException("model id cannot be null");
        var resolved = ALIASES.getOrDefault(id.trim().toLowerCase(), id.trim());
        var model = BY_ID.get(resolved);
        if (model == null) throw new IllegalArgumentException("unknown Grok Web model: " + id);
        return model;
    }

    static boolean supports(String tier, ModelSpec model) {
        return rank(parseTier(tier)) >= rank(model.minimumTier());
    }

    static Tier parseTier(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase()) {
            case "super", "paid" -> Tier.SUPER;
            case "heavy" -> Tier.HEAVY;
            default -> Tier.BASIC;
        };
    }

    private static int rank(Tier tier) {
        return switch (tier) { case BASIC -> 1; case SUPER -> 2; case HEAVY -> 3; };
    }

    enum Kind { CHAT, IMAGE, IMAGE_EDIT, VIDEO }
    record ModelSpec(String id, String mode, String protocolModel, Tier minimumTier, Kind kind) {}
}
