package com.wellsetups.wellui.core.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict graph JSON boundary; document codec has already enforced duplicate keys and byte/depth bounds. */
public final class LogicCodec {
    private LogicCodec() { }
    public static JsonElement encode(UiLogicGraph graph) {
        var tree = new com.google.gson.Gson().toJsonTree(graph).getAsJsonObject();
        for (int i = 0; i < graph.nodes().size(); i++) tree.getAsJsonArray("nodes").get(i).getAsJsonObject()
                .add("settings", new com.google.gson.Gson().toJsonTree(new TreeMap<>(graph.nodes().get(i).settings())));
        return tree;
    }
    public static UiLogicGraph decode(JsonElement value) {
        if (value == null) return UiLogicGraph.empty();
        try { return graph(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("logic.json", error); }
    }
    private static UiLogicGraph graph(JsonElement value) {
        JsonObject root = value.getAsJsonObject();
        fields(root, "schemaVersion", "nodes", "edges", "variables");
        number(root, "schemaVersion");
        int version = root.get("schemaVersion").getAsBigDecimal().intValueExact();
        var nodes = new ArrayList<UiLogicGraph.Node>();
        var edges = new ArrayList<UiLogicGraph.Edge>();
        var variables = new ArrayList<UiLogicGraph.Variable>();
        if (root.getAsJsonArray("nodes").size() > 128 || root.getAsJsonArray("edges").size() > 256
                || root.getAsJsonArray("variables").size() > 32) throw new IllegalArgumentException("logic.limit");
        for (var element : root.getAsJsonArray("nodes")) {
            var n = element.getAsJsonObject();
            fields(n, "id", "type", "x", "y", "settings");
            Map<String, LogicValue> settings = new TreeMap<>();
            if (n.getAsJsonObject("settings").size() > 12) throw new IllegalArgumentException("logic.settings");
            n.getAsJsonObject("settings").entrySet().forEach(e -> settings.put(e.getKey(), scalar(e.getValue())));
            nodes.add(new UiLogicGraph.Node(text(n, "id"), text(n, "type"), number(n, "x"), number(n, "y"), settings));
        }
        for (var element : root.getAsJsonArray("edges")) {
            var e = element.getAsJsonObject();
            fields(e, "from", "output", "to", "input");
            edges.add(new UiLogicGraph.Edge(text(e, "from"), text(e, "output"), text(e, "to"), text(e, "input")));
        }
        for (var element : root.getAsJsonArray("variables")) {
            var v = element.getAsJsonObject();
            fields(v, "name", "type", "initial");
            variables.add(new UiLogicGraph.Variable(text(v, "name"), LogicType.valueOf(text(v, "type")), scalar(v.get("initial"))));
        }
        return new UiLogicGraph(version, nodes, edges, variables);
    }
    private static LogicValue scalar(JsonElement element) {
        var value = element.getAsJsonObject();
        fields(value, "type", "value");
        return new LogicValue(LogicType.valueOf(text(value, "type")), text(value, "value"));
    }
    private static void fields(JsonObject value, String... keys) {
        if (!value.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("logic.fields");
    }
    private static String text(JsonObject value, String key) {
        var item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("logic.value");
        return item.getAsString();
    }
    private static double number(JsonObject value, String key) {
        var item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("logic.value");
        return item.getAsDouble();
    }
}
