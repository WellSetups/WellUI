package com.wellsetups.wellui.api.animation;

import java.util.List;
import java.util.Objects;

public record UiAnimationTrack(String component, UiAnimatedProperty property, List<UiKeyframe> keyframes) {
    public UiAnimationTrack {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(property, "property");
        keyframes = List.copyOf(keyframes);
    }
}
