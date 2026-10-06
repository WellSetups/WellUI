package com.wellsetups.wellui.core.control;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiControl;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.core.screen.ScreenScene;

/** One owner-thread route: modal first, then reverse painter order, clipped coordinates and disabled occlusion. */
public final class ControlRouter {
    public record Activation(ButtonComponent button, String option) { }
    public record Popup(String component, Viewport.Rect bounds, double rowHeight, int first, int rows) {
        public int option(double x, double y) { return bounds.contains(x, y) ? first + (int) ((y - bounds.y()) / rowHeight) : -1; }
    }
    private final UiDocument document;
    private final ControlState state;
    private int first;
    public ControlRouter(UiDocument document, ControlState state) {
        this.document = document;
        this.state = state;
    }
    public Popup popup(ScreenScene scene) {
        String id = state.overlay();
        if (id.isEmpty() || state.definition(id).kind() != UiControl.Kind.SELECT) return null;
        var p = scene.placement(id);
        var bottom = scene.transform(id).point(p.x(), p.y() + p.height());
        double height = Math.max(20, Math.min(60, p.height()));
        int rows = Math.max(1, Math.min(Math.min(8, state.definition(id).options().size()), (int) (document.height() / height)));
        first = Math.max(0, Math.min(state.definition(id).options().size() - rows, first));
        double x = Math.max(0, Math.min(document.width() - p.width(), bottom.x()));
        double y = bottom.y() + height * rows <= document.height() ? bottom.y() : bottom.y() - p.height() - height * rows;
        y = Math.max(0, Math.min(document.height() - height * rows, y));
        return new Popup(id, new Viewport.Rect(x, y, p.width(), height * rows), height, first, rows);
    }
    public ButtonComponent hover(ScreenScene scene, double x, double y) {
        if (!state.overlay().isEmpty()) {
            var popup = popup(scene);
            if (popup == null) return null;
            int option = popup.option(x, y);
            return option < 0 || state.definition(popup.component()).options().get(option).disabled() ? null : (ButtonComponent) scene.placement(popup.component()).component();
        }
        var hit = scene.hit(x, y, com.wellsetups.wellui.core.logic.LogicVisuals.dynamic(document) || !document.animations().isEmpty() ? 1.0 / 16 : Double.MIN_VALUE);
        return hit != null && !state.disabled(hit.id()) ? hit : null;
    }
    public Activation click(ScreenScene scene, double x, double y) {
        if (!state.overlay().isEmpty()) {
            var popup = popup(scene);
            if (popup == null) return null;
            int index = popup.option(x, y);
            if (index < 0) { state.overlay("");
                return null;
            }
            var option = state.definition(popup.component()).options().get(index);
            return option.disabled() ? null : new Activation((ButtonComponent) scene.placement(popup.component()).component(), option.value());
        }
        var placements = scene.placements();
        for (int i = placements.size() - 1; i >= 0; i--) {
            var p = placements.get(i);
            String id = p.component().id();
            var local = scene.transform(id).inverse(x, y);
            if (scene.opacity(id) < 1.0 / 16 || !scene.withinClip(id, x, y) || !new Viewport.Rect(p.x(), p.y(), p.width(), p.height()).contains(local.x(), local.y())) continue;
            var viewport = scene.viewports().get(id);
            if (viewport != null && viewport.scrollbar(local.x(), local.y()) && state.usable(id)) {
                state.set(id, LogicValue.number(viewport.fromScrollbar(local.x(), local.y())));
                return null;
            }
            break;
        }
        var hit = hover(scene, x, y);
        if (hit == null) { state.blur();
            return null;
        }
        return new Activation(hit, null);
    }
    /** Called only after runtime permission, session identity, cooldown and cancellable click checks. */
    public boolean activate(Activation activation) {
        String id = activation.button().id();
        var c = document.controls().get(id);
        if (c == null) { state.blur();
            return false;
        }
        if (!state.usable(id)) return true;
        state.focus(id);
        if (activation.option() != null) {
            if (state.overlay().equals(id) && c.kind() == UiControl.Kind.SELECT) {
                state.edit(id, LogicValue.string(activation.option()));
                state.overlay("");
            }
            return true;
        }
        switch (c.kind()) {
            case CHECKBOX, TOGGLE -> state.edit(id, LogicValue.bool(!state.value(id).bool()));
            case SLIDER -> state.active(state.active().equals(id) ? "" : id);
            case SELECT -> { first = 0;
                state.overlay(id);
            }
            case INPUT -> { if (!c.flag("readOnly")) state.overlay(id); }
            default -> { }
        }
        return true;
    }
    public void move(ScreenScene scene, double x, double y) {
        String id = state.active();
        if (id.isEmpty() || !state.overlay().isEmpty() || !state.usable(id)) return;
        var p = scene.placement(id);
        var local = scene.transform(id).inverse(x, y);
        double knob = ControlPaint.knobSize(p);
        state.drag(id, local.x() - p.x() - knob / 2, p.width() - knob);
    }
    public void scroll(ScreenScene scene, double x, double y, int direction) {
        if (direction == 0) return;
        if (!state.overlay().isEmpty()) {
            var popup = popup(scene);
            if (popup != null) first = Math.max(0, Math.min(state.definition(popup.component()).options().size() - popup.rows(), first + direction));
            return;
        }
        // A later visible element occludes a viewport as well as a button; one wheel input has one owner.
        var placements = scene.placements();
        for (int i = placements.size() - 1; i >= 0; i--) {
            var p = placements.get(i);
            String id = p.component().id();
            var local = scene.transform(id).inverse(x, y);
            if (scene.opacity(id) < 1.0 / 16 || !scene.withinClip(id, x, y)
                    || !new Viewport.Rect(p.x(), p.y(), p.width(), p.height()).contains(local.x(), local.y())) continue;
            String owner = scene.clipOwner(id);
            if (owner == null && scene.viewports().containsKey(id)) owner = id;
            if (owner != null && state.usable(owner)) {
                var metrics = scene.viewports().get(owner);
                state.set(owner, LogicValue.number(state.value(owner).number() + direction * metrics.speed()));
            } else if (document.controls().containsKey(id) && state.definition(id).kind() == UiControl.Kind.SLIDER && state.usable(id)) {
                state.edit(id, LogicValue.number(state.value(id).number() + direction * state.definition(id).number("step", 1)));
            }
            return;
        }
    }
}
