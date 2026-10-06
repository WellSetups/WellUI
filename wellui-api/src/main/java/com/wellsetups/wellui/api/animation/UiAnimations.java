package com.wellsetups.wellui.api.animation;

import java.util.List;

/** Immutable definitions only; playback belongs to a session. */
public record UiAnimations(int schemaVersion, List<UiAnimation> items) {
    public UiAnimations { items = List.copyOf(items); }
    public static UiAnimations empty() { return new UiAnimations(1, List.of()); }
    public boolean isEmpty() { return items.isEmpty(); }
}
