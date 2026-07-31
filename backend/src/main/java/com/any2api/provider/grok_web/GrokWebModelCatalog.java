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

    private GrokWebModelCatalog() {}

    static List<String> modelIds() { return MODELS.stream().map(ModelSpec::id).toList(); }

    static ModelSpec require(String id) {
        var model = BY_ID.get(id);
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
