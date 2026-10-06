package com.wellsetups.wellui.api.animation;

import java.util.Objects;

public record UiKeyframe(int tick, double value, Easing easing) {
    public enum Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }
    public UiKeyframe { Objects.requireNonNull(easing, "easing"); }
}
