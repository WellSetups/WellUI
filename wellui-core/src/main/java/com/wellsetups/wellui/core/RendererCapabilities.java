package com.wellsetups.wellui.core;

import java.util.List;
import java.util.Set;

/** Internal surface contract; capabilities describe actual input/render semantics, not aspirations. */
public record RendererCapabilities(String id, Set<Feature> features, List<String> warningKeys) {
    public enum Feature {
        REFERENCE_CANVAS, IMAGES, BAKED_TEXT, BAKED_OPACITY, BAKED_LAYERS, CANVAS_CLIPPING,
        NATIVE_CURSOR, NATIVE_HOVER, LEFT_CLICK, RIGHT_CLICK, SEMANTIC_ACTIVATION,
        SCREEN_POSITIONING, SCROLL, ANIMATIONS, TEXT_INPUT, LOOK_CURSOR, LOGICAL_HOVER, CONTROLS, FORMS, FOCUS, VIEWPORT_CLIPPING
    }
    public RendererCapabilities {
        features = Set.copyOf(features);
        warningKeys = List.copyOf(warningKeys);
    }
    public boolean supports(Feature feature) { return features.contains(feature); }
    public String unsupported(com.wellsetups.wellui.api.document.UiDocument document) {
        if (!document.controls().isEmpty() && !supports(Feature.CONTROLS)) return "control.renderer_unsupported";
        return "";
    }
    public static RendererCapabilities inventory() {
        return new RendererCapabilities("inventory", Set.of(Feature.NATIVE_CURSOR, Feature.NATIVE_HOVER,
                Feature.LEFT_CLICK, Feature.RIGHT_CLICK), List.of("deploy.inventory"));
    }
    public static RendererCapabilities hud() {
        return new RendererCapabilities("glyph_hud_poc", Set.of(Feature.REFERENCE_CANVAS, Feature.IMAGES,
                Feature.BAKED_TEXT, Feature.BAKED_OPACITY, Feature.BAKED_LAYERS, Feature.CANVAS_CLIPPING,
                Feature.SEMANTIC_ACTIVATION), List.of("hud.visual_limits", "hud.input_limits", "hud.pack_limits"));
    }
}
