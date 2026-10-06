package com.wellsetups.wellui.api.logic;

import java.util.List;
import java.util.Map;

/** Immutable, platform-independent behavior attached to one atomic project revision. */
public record UiLogicGraph(int schemaVersion, List<Node> nodes, List<Edge> edges, List<Variable> variables) {
    public UiLogicGraph { nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        variables = List.copyOf(variables);
        }
    public record Node(String id, String type, double x, double y, Map<String, LogicValue> settings) {
        public Node { settings = Map.copyOf(settings); }
    }
    public record Edge(String from, String output, String to, String input) { }
    public record Variable(String name, LogicType type, LogicValue initial) { }
    public static UiLogicGraph empty() { return new UiLogicGraph(1, List.of(), List.of(), List.of()); }
    public boolean isEmpty() { return nodes.isEmpty() && edges.isEmpty() && variables.isEmpty(); }
}
