package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import com.wellsetups.wellui.core.LayoutEngine;
import java.util.HashMap;
import java.util.Map;

/** Immutable event/port indexes, prepared with the exact deployed visual revision. */
public final class CompiledLogic {
    private final Map<String, UiLogicGraph.Node> nodes;
    private final Map<String, UiLogicGraph.Edge> inputs;
    private final Map<String, String> outputs;
    private final Map<String, String> entries;
    public CompiledLogic(UiDocument document) {
        if (new LogicValidator().validate(document, null, null).stream().anyMatch(i -> !i.warning())) throw new IllegalArgumentException("logic.invalid");
        var indexed = new HashMap<String, UiLogicGraph.Node>();
        var incoming = new HashMap<String, UiLogicGraph.Edge>();
        var outgoing = new HashMap<String, String>();
        var events = new HashMap<String, String>();
        for (var node : document.logic().nodes()) {
            indexed.put(node.id(), node);
            if (node.type().startsWith("on_")) events.put(node.type() + ":" + LogicCatalog.text(node, node.type().equals("on_animation_complete") ? "animation" : "component"), node.id());
        }
        for (var edge : document.logic().edges()) {
            incoming.put(edge.to() + ":" + edge.input(), edge);
            if (LogicCatalog.spec(indexed.get(edge.from()), document.logic()).outputs().get(edge.output()) == com.wellsetups.wellui.api.logic.LogicType.EXECUTION)
                outgoing.put(edge.from() + ":" + edge.output(), edge.to());
        }
        int serial = 0;
        for (var placed : new LayoutEngine().layout(document, document.width(), document.height())) {
            if (!(placed.component() instanceof ButtonComponent button) || document.controls().containsKey(button.id()) || events.containsKey("on_click:" + button.id())) continue;
            String event = "@legacy-event-" + serial, action = "@legacy-action-" + serial++;
            indexed.put(event, new UiLogicGraph.Node(event, "on_click", 0, 0, Map.of("component", LogicValue.string(button.id()))));
            indexed.put(action, new UiLogicGraph.Node(action, button.action().type(), 0, 0, button.action().type().equals("close_ui")
                    ? Map.of() : Map.of("value", LogicValue.string(button.action().value()))));
            events.put("on_click:" + button.id(), event);
            outgoing.put(event + ":next", action);
        }
        nodes = Map.copyOf(indexed);
        inputs = Map.copyOf(incoming);
        outputs = Map.copyOf(outgoing);
        entries = Map.copyOf(events);
    }
    public UiLogicGraph.Node node(String id) { return nodes.get(id); }
    public UiLogicGraph.Edge input(String id, String port) { return inputs.get(id + ":" + port); }
    public String next(String id, String port) { return outputs.get(id + ":" + port); }
    public String entry(String event, String component) { return entries.get(event + ":" + component); }
    public boolean has(String event, String component) { return entry(event, component) != null; }
}
