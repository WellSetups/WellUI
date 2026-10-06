package com.wellsetups.wellui.api.document;

import java.util.List;
import java.util.Objects;

/** A container that establishes a local coordinate system for its children. */
public record PanelComponent(String id, Layout layout, Style style, List<UiComponent> children)
        implements UiComponent {
    /** Takes an immutable snapshot of its children. */
    public PanelComponent {
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
