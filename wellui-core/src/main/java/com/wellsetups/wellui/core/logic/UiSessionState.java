package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.document.Style;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mutable owner-thread state, never persisted and never shared between sessions. */
public final class UiSessionState {
    private final Map<String, LogicValue> variables = new HashMap<>();
    private final Map<String, Boolean> visibility = new HashMap<>();
    private final Map<String, String> text = new HashMap<>();
    private final Map<String, Double> opacity = new HashMap<>();
    private long revision;
    private UiDocument source, cached;
    private long cachedRevision = -1;
    private com.wellsetups.wellui.core.control.ControlState controls;
    public UiSessionState(UiDocument document) {
        this(document.logic());
        controls = new com.wellsetups.wellui.core.control.ControlState(document, () -> revision++);
    }
    public com.wellsetups.wellui.core.control.ControlState controls() {
        if (controls == null) throw new IllegalStateException("control.unbound");
        return controls;
    }
    public UiSessionState(UiLogicGraph graph) { graph.variables().forEach(v -> variables.put(v.name(), v.initial())); }
    public Map<String, LogicValue> variables() { return Map.copyOf(variables); }
    public LogicValue variable(String name) {
        LogicValue value = variables.get(name);
        if (value == null) throw new IllegalArgumentException("logic.variable");
        return value;
    }
    public LogicValue variable(String name, LogicValue value) {
        var old = variable(name);
        value.require(old.type());
        variables.put(name, value);
        revision++;
        return old;
    }
    public void visible(String id, boolean value) { visibility.put(id, value);
        revision++;
        }
    public void text(String id, String value) {
        if (value.length() > 2048) throw new IllegalArgumentException("logic.text_limit");
        text.put(id, value);
        revision++;
    }
    public void opacity(String id, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("logic.opacity");
        opacity.put(id, value);
        revision++;
    }
    public long revision() { return revision; }
    public Map<String, Double> scrollPositions(UiDocument document) {
        var result = new HashMap<String, Double>();
        if (controls != null) document.controls().forEach((id, c) -> {
            if (c.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SCROLL) result.put(id, controls.value(id).number());
        });
        return Map.copyOf(result);
    }
    public com.wellsetups.wellui.core.screen.ScreenScene scene(UiDocument document) {
        return new com.wellsetups.wellui.core.screen.ScreenScene(apply(document), Map.of(), scrollPositions(document));
    }
    public UiDocument apply(UiDocument document) {
        if (source != document || revision != cachedRevision) {
            cached = new UiDocument(document.schemaVersion(), document.id(), document.name(), document.width(), document.height(), apply(document.children()), document.logic(), document.animations(), document.controls());
            source = document;
            cachedRevision = revision;
        }
        return cached;
    }
    private List<UiComponent> apply(List<UiComponent> nodes) {
        return nodes.stream().map(n -> {
            Style s = n.style();
            double alpha = visibility.getOrDefault(n.id(), true) ? opacity.getOrDefault(n.id(), s.opacity()) : 0;
            var style = new Style(s.background(), s.color(), alpha, s.fontSize());
            if (n instanceof PanelComponent panel) return (UiComponent) new PanelComponent(n.id(), n.layout(), style, apply(panel.children()));
            if (n instanceof TextComponent value) return new TextComponent(n.id(), n.layout(), style, text.getOrDefault(n.id(), value.text()));
            if (n instanceof ButtonComponent value) {
                String label = text.getOrDefault(n.id(), value.text());
                if (controls != null) label = controls.label(n.id(), label);
                return new ButtonComponent(n.id(), n.layout(), style, label.substring(0, Math.min(256, label.length())), value.action());
            }
            var image = (ImageComponent) n;
            return new ImageComponent(n.id(), n.layout(), style, image.asset());
        }).toList();
    }
}
