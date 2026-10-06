package com.wellsetups.wellui.core.control;

import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.LayoutEngine;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared quantization and viewport bounds for prepared pixels, scroll state and pointer routing. */
public final class Viewport {
    private Viewport() { }
    public record Rect(double x, double y, double width, double height) {
        public boolean contains(double px, double py) { return px >= x && py >= y && px < x + width && py < y + height; }
    }
    public record Metrics(Rect bounds, Set<String> children, boolean horizontal, double maximum, double speed, int steps) {
        public Metrics { children = Set.copyOf(children); }
        public double offset(double requested) {
            if (!Double.isFinite(requested)) throw new IllegalArgumentException("control.scroll_value");
            if (requested >= maximum) return maximum;
            return Math.min(maximum, Math.max(0, Math.round(Math.min(maximum, Math.max(0, requested)) / speed) * speed));
        }
        public double at(int step) { return Math.min(maximum, Math.max(0, step) * speed); }
        public int index(double offset) { return Math.min(steps - 1, (int) Math.ceil(offset / speed)); }
        public boolean scrollbar(double x, double y) {
            return maximum > 0 && bounds.contains(x, y) && (horizontal ? y >= bounds.y() + bounds.height() - 6 : x >= bounds.x() + bounds.width() - 6);
        }
        public double fromScrollbar(double x, double y) {
            double length = horizontal ? bounds.width() : bounds.height();
            double thumb = Math.max(8, length * length / (length + maximum));
            double position = (horizontal ? x - bounds.x() : y - bounds.y()) - thumb / 2;
            return offset(Math.max(0, Math.min(1, position / Math.max(1, length - thumb))) * maximum);
        }
    }
    public static Metrics metrics(UiDocument document, String id) {
        var placements = new LayoutEngine().reference(document);
        var p = placements.stream().filter(v -> v.component().id().equals(id)).findFirst().orElseThrow();
        var panel = (PanelComponent) p.component();
        var control = document.controls().get(id);
        var children = new HashSet<String>();
        collect(panel.children(), children);
        boolean horizontal = control.text("axis", "y").equals("x");
        double extent = horizontal ? p.x() + p.width() : p.y() + p.height();
        for (var child : placements) if (children.contains(child.component().id())) extent = Math.max(extent,
                horizontal ? child.x() + child.width() : child.y() + child.height());
        double maximum = control.text("overflow", "SCROLL").equals("SCROLL") ? extent - (horizontal ? p.x() + p.width() : p.y() + p.height()) : 0;
        double speed = control.number("scrollSpeed", 40);
        return new Metrics(new Rect(p.x(), p.y(), p.width(), p.height()), children, horizontal, maximum, speed, 1 + (int) Math.ceil(maximum / speed));
    }
    private static void collect(List<UiComponent> nodes, Set<String> ids) {
        for (var node : nodes) {
            ids.add(node.id());
            if (node instanceof PanelComponent panel) collect(panel.children(), ids);
        }
    }
}
