package com.wellsetups.wellui.core.screen;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.LayoutEngine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable once-per-revision placements, effective opacity and painter-order hit testing. */
public final class ScreenScene {
    private final UiDocument document;
    private final List<LayoutEngine.PlacedComponent> placements;
    private final Map<String, LayoutEngine.PlacedComponent> index;
    private final Map<String, Double> opacity = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, com.wellsetups.wellui.core.animation.Affine2> transforms = new HashMap<>();
    private final Map<String, Double> scroll;
    private final Map<String, String> clippedBy = new HashMap<>();
    private final Map<String, com.wellsetups.wellui.core.control.Viewport.Metrics> viewports = new HashMap<>();
    public ScreenScene(UiDocument document) { this(document, Map.of()); }
    public ScreenScene(UiDocument document, Map<com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property, Double> overlay) {
        this(document, overlay, Map.of());
    }
    public ScreenScene(UiDocument document, Map<com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property, Double> overlay, Map<String, Double> scroll) {
        this.document = document;
        this.scroll = Map.copyOf(scroll);
        placements = new LayoutEngine().layout(document, document.width(), document.height());
        index = new HashMap<>();
        placements.forEach(p -> index.put(p.component().id(), p));
        parents(document.children(), "");
        document.controls().forEach((id, control) -> {
            if (control.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SCROLL && !control.text("overflow", "SCROLL").equals("VISIBLE")) {
                var metrics = com.wellsetups.wellui.core.control.Viewport.metrics(document, id);
                viewports.put(id, metrics);
                metrics.children().forEach(child -> clippedBy.put(child, id));
            }
        });
        compose(document.children(), 1, com.wellsetups.wellui.core.animation.Affine2.IDENTITY, overlay);
    }
    public ScreenScene(ScreenScene base, Map<com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property, Double> overlay) {
        document = base.document;
        placements = base.placements;
        index = base.index;
        parents.putAll(base.parents);
        scroll = base.scroll;
        clippedBy.putAll(base.clippedBy);
        viewports.putAll(base.viewports);
        compose(document.children(), 1, com.wellsetups.wellui.core.animation.Affine2.IDENTITY, overlay);
    }
    private void compose(List<UiComponent> children, double alpha, com.wellsetups.wellui.core.animation.Affine2 parent,
                         Map<com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property, Double> overlay) {
        for (var child : children) {
            var p = index.get(child.id());
            double opacity = alpha * child.style().opacity() * value(overlay, child.id(), "OPACITY") * value(overlay, child.id(), "VISIBILITY");
            this.opacity.put(child.id(), opacity);
            var local = com.wellsetups.wellui.core.animation.Affine2.local(p.x() + p.width() / 2, p.y() + p.height() / 2,
                    value(overlay, child.id(), "TRANSLATION_X"), value(overlay, child.id(), "TRANSLATION_Y"),
                    value(overlay, child.id(), "SCALE_X"), value(overlay, child.id(), "SCALE_Y"), value(overlay, child.id(), "ROTATION_Z"));
            var matrix = parent.multiply(local);
            transforms.put(child.id(), matrix);
            if (child instanceof PanelComponent panel) {
                var viewport = viewports.get(child.id());
                var childMatrix = matrix;
                if (viewport != null) {
                    double offset = viewport.offset(scroll.getOrDefault(child.id(), 0.0));
                    childMatrix = matrix.multiply(com.wellsetups.wellui.core.animation.Affine2.local(0, 0, viewport.horizontal() ? -offset : 0, viewport.horizontal() ? 0 : -offset, 1, 1, 0));
                }
                compose(panel.children(), opacity, childMatrix, overlay);
            }
        }
    }
    private double value(Map<com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property, Double> overlay, String id, String property) {
        var p = com.wellsetups.wellui.api.animation.UiAnimatedProperty.valueOf(property);
        return overlay.getOrDefault(new com.wellsetups.wellui.core.animation.UiAnimationPlayer.Property(id, p), p.baseline());
    }
    public com.wellsetups.wellui.core.animation.Affine2 transform(String id) {
        return transforms.getOrDefault(id, com.wellsetups.wellui.core.animation.Affine2.IDENTITY);
    }
    public List<LayoutEngine.PlacedComponent> placements() { return placements; }
    public LayoutEngine.PlacedComponent placement(String id) { return index.get(id); }
    public String clipOwner(String id) { return clippedBy.get(id); }
    public Map<String, com.wellsetups.wellui.core.control.Viewport.Metrics> viewports() { return Map.copyOf(viewports); }
    public boolean withinClip(String id, double x, double y) {
        String owner = clippedBy.get(id);
        if (owner == null) return true;
        var point = transform(owner).inverse(x, y);
        var viewport = viewports.get(owner);
        return viewport.bounds().contains(point.x(), point.y()) && !viewport.scrollbar(point.x(), point.y());
    }
    public double opacity(String id) { return opacity.getOrDefault(id, 0.0); }
    public double relativeOpacity(String id, String ancestor) {
        double alpha = 1;
        for (String current = id; !current.isEmpty() && !current.equals(ancestor); current = parents.getOrDefault(current, "")) alpha *= index.get(current).component().style().opacity();
        return alpha;
    }
    private void parents(List<UiComponent> nodes, String parent) {
        for (var node : nodes) {
            parents.put(node.id(), parent);
            if (node instanceof PanelComponent panel) parents(panel.children(), node.id());
        }
    }
    public List<ButtonComponent> buttons() {
        return placements.stream().map(LayoutEngine.PlacedComponent::component)
                .filter(ButtonComponent.class::isInstance).map(ButtonComponent.class::cast).toList();
    }
    public ButtonComponent hit(double x, double y) { return hit(x, y, Double.MIN_VALUE); }
    public ButtonComponent hit(double x, double y, double minimumOpacity) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= document.width() || y >= document.height()) return null;
        for (int i = placements.size() - 1; i >= 0; i--) {
            var p = placements.get(i);
            var local = transform(p.component().id()).inverse(x, y);
            if (withinClip(p.component().id(), x, y) && opacity(p.component().id()) >= minimumOpacity && local.x() >= p.x() && local.y() >= p.y()
                    && local.x() < p.x() + p.width() && local.y() < p.y() + p.height()) {
                return p.component() instanceof ButtonComponent button ? button : null;
            }
        }
        return null;
    }
}
