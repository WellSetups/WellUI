package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.core.screen.DynamicText;
import com.wellsetups.wellui.core.screen.ScreenScene;
import com.wellsetups.wellui.core.screen.Vec3;
import java.util.ArrayList;
import java.util.List;

/** At most 16 independent layers, 17 composited static runs, and 16 text displays. All operations are player-owner packets, never IO. */
final class DynamicScreen {
    private static final class Layer {
        final ScreenPackBuilder.Layer prepared;
        final int item, text;
        final double depth;
        String model = "", content = "";
        double opacity = -1;
        long nextTextTransform;
        Vec3 textPosition;
        com.wellsetups.wellui.platform.screen.DisplayTransform itemPose, textPose;
        Layer(ScreenPackBuilder.Layer prepared, int item, int text, double depth) {
            this.prepared = prepared;
            this.item = item;
            this.text = text;
            this.depth = depth;
        }
    }
    private final ScreenRenderer.Session session;
    private final List<Layer> layers = new ArrayList<>();
    private long revision = -1, animationRevision = -1, tick = -1, nextTransform, resetTick = -1;
    private ScreenScene baseScene;
    private final boolean animated;
    private String hover;
    private ScreenScene scene;
    DynamicScreen(ScreenRenderer.Session session) {
        this.session = session;
        scene = session.pack.scene();
        baseScene = scene;
        animated = !session.document.animations().isEmpty();
    }
    ScreenScene scene() { return scene; }
    void open() {
        int index = 0;
        for (var prepared : session.pack.layers()) {
            if (prepared.component().startsWith("@popup-")) continue;
            var layer = new Layer(prepared, session.viewer.allocateEntityId(), prepared.dynamicText() ? session.viewer.allocateEntityId() : -1, .0003 * (++index));
            layers.add(layer);
            layer.model = prepared.models().get(0);
            session.viewer.spawn(layer.item, position(layer), prepared.width() * scale(), prepared.height() * scale(), session.yaw, session.pitch, layer.model);
            if (layer.text >= 0) {
                var component = scene.placements().stream().filter(p -> p.component().id().equals(prepared.component())).findFirst().orElseThrow().component();
                layer.textPosition = position(layer);
                session.viewer.textSpawn(layer.text, layer.textPosition, component.style().fontSize() / 10.0 * scale() / .025,
                        session.yaw, session.pitch, "", component.style().color(), 0);
            }
        }
        refresh(true);
    }
    private double scale() { return session.plane.width() / session.document.width(); }
    private Vec3 position(Layer layer) {
        var p = layer.prepared;
        return session.plane.point(p.x() + p.width() / 2, p.y() + p.height() / 2, layer.depth);
    }
    void refresh(boolean force) {
        long now = session.animations == null ? 0 : session.animations.ticks();
        long version = session.animations == null ? 0 : session.animations.revision();
        boolean reset = force || revision != session.state.revision() || animationRevision != version;
        if (!reset && (!animated || tick == now) && java.util.Objects.equals(hover, session.hover.current())) return;
        if (revision != session.state.revision()) baseScene = session.state.scene(session.document);
        scene = animated && session.animations != null ? new ScreenScene(baseScene, session.animations.overlay(0)) : baseScene;
        session.state.controls().reconcile(id -> scene.opacity(id) >= 1.0 / 16);
        revision = session.state.revision();
        animationRevision = version;
        tick = now;
        if (reset) resetTick = now;
        int duration = now == resetTick || session.animations == null ? 0 : session.animations.horizon();
        boolean send = animated && (reset || now >= nextTransform);
        ScreenScene target = animated && duration > 0 ? new ScreenScene(baseScene, session.animations.overlay(duration)) : scene;
        if (send) { nextTransform = now + Math.max(1, duration);
            if (duration > 0) session.animations.segment();
        }
        hover = session.hover.current();
        for (var layer : layers) {
            var p = layer.prepared;
            boolean isStatic = p.component().startsWith("@static-");
            boolean knob = p.component().startsWith("@knob-");
            String owner = knob ? p.component().substring(6) : p.component();
            double alpha = isStatic ? 1 : scene.opacity(owner);

            int level = Math.max(0, Math.min(8, (int) Math.round(alpha * 8)));
            List<String> models = p.component().equals(hover) && !p.hover().isEmpty() ? p.hover() : p.models();
            if (!p.variants().isEmpty()) {
                var control = session.document.controls().get(p.component());
                String key;
                var viewport = scene.viewports().get(p.component());
                if (viewport != null) {
                    int step = viewport.index(session.state.controls().value(p.component()).number());
                    key = "scroll:" + step + ":" + (hover != null && viewport.children().contains(hover) ? hover : "");
                } else {
                    boolean checked = control.valueType() == com.wellsetups.wellui.api.logic.LogicType.BOOLEAN && session.state.controls().value(p.component()).bool();
                    key = session.state.controls().state(p.component(), hover).name() + ":" + checked;
                }
                models = p.variants().getOrDefault(key, models);
            }
            String model = models.get(level);
            boolean modelChanged = !model.equals(layer.model);
            if (modelChanged) { session.viewer.model(layer.item, model);
                layer.model = model;
                if (session.animations != null) session.animations.packet(false);
                }
            if (animated && !isStatic && (send || modelChanged)) transform(layer, target, duration, false,
                    p.x() + p.width() / 2, p.y() + p.height() / 2, reset || modelChanged);
            if (knob) transform(layer, scene, 0, false, p.x() + p.width() / 2, p.y() + p.height() / 2, reset);
            if (layer.text < 0) continue;
            var placement = scene.placements().stream().filter(item -> item.component().id().equals(p.component())).findFirst().orElseThrow();
            var node = placement.component();
            String text = node instanceof TextComponent label ? label.text() : ((ButtonComponent) node).text();
            // Animated text maps native alpha separately; parents still supply inherited visibility.
            var control = session.document.controls().get(p.component());
            double inset = control == null ? 0 : switch (control.kind()) {
                case CHECKBOX, TOGGLE -> Math.min(p.width() / 2, Math.min(p.height() * .65, 40) + 12);
                default -> 8;
            };
            double labelHeight = control != null && control.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SLIDER ? p.height() * .6 : p.height();
            var block = DynamicText.layout(text, Math.max(1, p.width() - inset - (control == null ? 0 : 16)), labelHeight, node.style().fontSize());
            double unit = node.style().fontSize() / 10.0;
            double width = block.columns() * 8 * unit;
            double left = control != null ? p.x() + inset : node instanceof ButtonComponent ? p.x() + (p.width() - width) / 2 : p.x();
            var position = session.plane.point(left + width / 2 - unit, p.y() + (block.rows() * 10 - 1) * unit, layer.depth + .0001);
            if (animated) {
                // Text alpha and transform share one native interpolation clock. Sample changing alpha at one tick.
                ScreenScene next = duration > 0 ? new ScreenScene(baseScene, session.animations.overlay(1)) : scene;
                boolean alphaMoving = Math.abs(next.opacity(p.component()) - alpha) > 1e-9;
                int textDuration = alphaMoving ? 1 : duration;
                ScreenScene textTarget = alphaMoving ? next : target;
                double textAlpha = alphaMoving ? next.opacity(p.component()) : alpha;
                boolean changed = !block.text().equals(layer.content) || textAlpha != layer.opacity;
                if (changed) {
                    session.viewer.text(layer.text, block.text(), node.style().color(), textAlpha);
                    layer.content = block.text();
                    layer.opacity = textAlpha;
                    session.animations.packet(false);
                }
                if (send || changed || alphaMoving || now >= layer.nextTextTransform) {
                    transform(layer, textTarget, textDuration, true,
                            left + width / 2 - unit, p.y() + (block.rows() * 10 - 1) * unit, reset || changed || alphaMoving);
                    layer.nextTextTransform = now + Math.max(1, textDuration);
                }
            } else {
                if (!position.equals(layer.textPosition)) {
                    session.viewer.move(layer.text, position);
                    layer.textPosition = position;
                }
                if (!block.text().equals(layer.content) || alpha != layer.opacity) {
                    session.viewer.text(layer.text, block.text(), node.style().color(), level / 8.0);
                    layer.content = block.text();
                    layer.opacity = alpha;
                }
            }
        }
    }
    private void transform(Layer layer, ScreenScene target, int duration, boolean text, double x, double y, boolean reset) {
        var p = layer.prepared;
        boolean knob = p.component().startsWith("@knob-");
        String owner = knob ? p.component().substring(6) : p.component();
        var matrix = target.transform(owner);
        if (knob) x += session.state.controls().fraction(owner) * (scene.placement(owner).width() - p.width());
        var point = matrix.point(x, y);
        Vec3 anchor = position(layer);
        Vec3 translated = session.plane.point(point.x(), point.y(), layer.depth + (text ? .0001 : 0)).subtract(anchor);
        double w = p.width() * scale(), h = p.height() * scale();
        if (text) {
            var component = scene.placements().stream().filter(v -> v.component().id().equals(p.component())).findFirst().orElseThrow().component();
            w = component.style().fontSize() / 10.0 * scale() / .025;
            h = w;
        }
        var pose = com.wellsetups.wellui.platform.screen.DisplayTransform.of(translated, matrix, w, h, session.yaw, session.pitch);
        var previous = text ? layer.textPose : layer.itemPose;
        if (reset || !pose.equals(previous)) {
            session.viewer.transform(text ? layer.text : layer.item, pose, duration);
            if (session.animations != null) session.animations.packet(true);
            if (text) layer.textPose = pose;
            else layer.itemPose = pose;
        }
    }
    void translate(Vec3 offset) {
        for (var layer : layers) {
            session.viewer.move(layer.item, position(layer));
            if (layer.text >= 0) { layer.textPosition = animated ? position(layer) : layer.textPosition.add(offset);
                session.viewer.move(layer.text, layer.textPosition);
                }
        }
    }
    void close() {
        var ids = new ArrayList<Integer>();
        for (var layer : layers) { ids.add(layer.item);
            if (layer.text >= 0) ids.add(layer.text);
            }
        if (!ids.isEmpty()) session.viewer.destroy(ids.stream().mapToInt(Integer::intValue).toArray());
        layers.clear();
    }
}
