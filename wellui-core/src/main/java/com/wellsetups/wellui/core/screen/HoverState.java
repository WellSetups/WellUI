package com.wellsetups.wellui.core.screen;

import java.util.Objects;

/** Null is no hovered component; transitions distinguish enter, exit and unchanged. */
public final class HoverState {
    public record Transition(String exited, String entered, boolean changed) { }
    private String current;
    public String current() { return current; }
    public Transition update(String next) {
        boolean changed = !Objects.equals(current, next);
        Transition result = new Transition(changed ? current : null, changed ? next : null, changed);
        current = next;
        return result;
    }
}
