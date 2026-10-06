package com.wellsetups.wellui.api.animation;

import java.util.List;
import java.util.Objects;

public record UiAnimation(String id, String name, int duration, Loop loop, End end, List<UiAnimationTrack> tracks) {
    public enum Loop { NONE, LOOP }
    public enum End { RESTORE, HOLD_FINAL }
    public UiAnimation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(end, "end");
        tracks = List.copyOf(tracks);
    }
}
