package com.wellsetups.wellui.core.screen;

/** Pure selection policy: explicit SCREEN never silently degrades. */
public final class RendererSelection {
    public enum Mode { AUTO, SCREEN, HUD, INVENTORY }
    public record Decision(Mode requested, Mode selected, String reason) { }
    private RendererSelection() { }
    public static Decision select(Mode requested, String screenUnavailable, boolean fallback) {
        if (requested == Mode.INVENTORY || requested == Mode.HUD) return new Decision(requested, requested, "");
        if (screenUnavailable.isEmpty()) return new Decision(requested, Mode.SCREEN, "");
        return new Decision(requested, requested == Mode.AUTO && fallback ? Mode.INVENTORY : null, screenUnavailable);
    }
}
