package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import com.wellsetups.wellui.core.DocumentValidator;
import com.wellsetups.wellui.api.document.PanelComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authoritative structural/type validation, with optional deployment context. */
public final class LogicValidator {
    public record Issue(String code, String nodeId, boolean warning) { }
    public List<Issue> validate(UiDocument document, Set<String> projects, Boolean placeholders) {
        var graph = document.logic();
        var issues = new ArrayList<Issue>();
        if (graph.schemaVersion() != 1) issues.add(new Issue("logic.schema", "", false));
        if (graph.nodes().size() > 128 || graph.edges().size() > 256 || graph.variables().size() > 32) return List.of(new Issue("logic.limit", "", false));
        var nodes = new HashMap<String, UiLogicGraph.Node>();
        var vars = new HashMap<String, LogicType>();
        for (var variable : graph.variables()) {
            if (!DocumentValidator.validId(variable.name()) || variable.type() == null || variable.type() == LogicType.EXECUTION
                    || variable.initial() == null || variable.type() != variable.initial().type() || vars.put(variable.name(), variable.type()) != null)
                issues.add(new Issue("logic.variable", "", false));
        }
        var components = new HashMap<String, UiComponent>();
        var pending = new java.util.ArrayDeque<>(document.children());
        while (!pending.isEmpty() && components.size() <= 500) {
            var component = pending.removeFirst();
            if (components.put(component.id(), component) != null) continue;
            if (component instanceof PanelComponent panel) pending.addAll(panel.children());
        }
        if (LogicVisuals.dynamic(document) && components.size() > 16) issues.add(new Issue("logic.screen_layers", "", true));
        Set<String> entries = new HashSet<>();
        for (var node : graph.nodes()) {
            String id = node.id();
            var spec = LogicCatalog.spec(node, graph);
            if (!DocumentValidator.validId(id) || nodes.put(id, node) != null) issues.add(new Issue("logic.duplicate_id", id, false));
            if (!Double.isFinite(node.x()) || !Double.isFinite(node.y()) || Math.abs(node.x()) > 100000 || Math.abs(node.y()) > 100000) issues.add(new Issue("logic.position", id, false));
            if (spec == null) { issues.add(new Issue("logic.node_type", id, false));
                continue;
                }
            var keys = new HashSet<>(spec.settings().keySet());
            spec.inputs().forEach((key, type) -> { if (type != LogicType.EXECUTION) keys.add(key); });
            if (!keys.containsAll(node.settings().keySet())) issues.add(new Issue("logic.settings", id, false));
            spec.settings().forEach((key, type) -> { if (!node.settings().containsKey(key) || node.settings().get(key).type() != type) issues.add(new Issue("logic.setting", id, false)); });
            String component = LogicCatalog.text(node, "component");
            if (spec.settings().containsKey("component")) {
                UiComponent target = components.get(component);
                if (target == null || Set.of("on_click", "on_hover_enter", "on_hover_exit").contains(node.type()) && !(target instanceof ButtonComponent)
                        || node.type().equals("set_text") && !(target instanceof ButtonComponent || target instanceof TextComponent)) issues.add(new Issue("logic.component", id, false));
                var control = document.controls().get(component);
                String kind = switch (node.type()) {
                    case "input_value", "clear_input" -> "INPUT";
                    case "checkbox_value" -> "CHECKBOX";
                    case "toggle_value" -> "TOGGLE";
                    case "slider_value" -> "SLIDER";
                    case "selected_value" -> "SELECT";
                    case "form_valid", "submit_form", "reset_form", "on_submit" -> "FORM";
                    case "scroll_position", "scroll_to", "on_scroll" -> "SCROLL";
                    case "set_value", "set_disabled", "focus_component", "blur_component", "on_focus", "on_blur", "on_value_change", "validation_message" -> "ANY";
                    default -> "";
                };
                if (!kind.isEmpty() && (control == null || !kind.equals("ANY") && !control.kind().name().equals(kind))) issues.add(new Issue("control.logic_target", id, false));
                if (control != null && node.type().equals("set_value") && (!control.field() || !control.valueType().name().equals(LogicCatalog.text(node, "valueType")))) issues.add(new Issue("control.logic_type", id, false));
                if (control != null && Set.of("on_focus", "on_blur", "on_value_change", "focus_component").contains(node.type()) && !control.field()) issues.add(new Issue("control.logic_target", id, false));
            }
            if (spec.category().equals("event") && !entries.add(node.type() + ":" + (spec.settings().containsKey("animation") ? LogicCatalog.text(node, "animation") : component))) issues.add(new Issue("logic.duplicate_event", id, false));
            if (spec.settings().containsKey("animation") && document.animations().items().stream()
                    .noneMatch(a -> a.id().equals(LogicCatalog.text(node, "animation")))) issues.add(new Issue("animation.reference", id, false));
            String variable = LogicCatalog.text(node, "variable");
            if (spec.settings().containsKey("variable") && (!vars.containsKey(variable)
                    || node.type().equals("add_number") && vars.get(variable) != LogicType.NUMBER
                    || node.type().equals("toggle_boolean") && vars.get(variable) != LogicType.BOOLEAN)) issues.add(new Issue("logic.variable", id, false));
            if (node.type().equals("has_permission") && !LogicCatalog.text(node, "permission").matches("[a-zA-Z0-9_*][a-zA-Z0-9_.:*\\-]{0,127}")) issues.add(new Issue("logic.permission", id, false));
            if (node.type().equals("open_ui") && (!DocumentValidator.validId(LogicCatalog.text(node, "project"))
                    || projects != null && !projects.contains(LogicCatalog.text(node, "project")))) issues.add(new Issue("logic.project", id, false));
            var literal = node.settings().get("value");
            if (node.type().equals("set_opacity") && literal != null && literal.type() == LogicType.NUMBER
                    && (literal.number() < 0 || literal.number() > 1)) issues.add(new Issue("logic.opacity", id, false));
            if (node.type().equals("player_command") && literal != null && literal.type() == LogicType.STRING
                    && (literal.string().isBlank() || literal.string().startsWith("/") || literal.string().codePoints().anyMatch(Character::isISOControl)))
                issues.add(new Issue("logic.command", id, false));
            if (node.type().equals("delay")) {
                var ticks = node.settings().get("ticks");
                if (ticks == null || ticks.type() != LogicType.NUMBER || ticks.number() < 1 || ticks.number() > 1200 || ticks.number() != Math.rint(ticks.number())) issues.add(new Issue("logic.delay", id, false));
            }
            if (spec.settings().containsKey("placeholder")) {
                if (!LogicCatalog.text(node, "placeholder").matches("%[a-zA-Z0-9_:. -]{1,128}%")) issues.add(new Issue("logic.placeholder", id, false));
                if (Boolean.FALSE.equals(placeholders)) issues.add(new Issue("logic.placeholder_unavailable", id, true));
            }
            if (node.type().equals("placeholder_numeric") && !Set.of("eq", "ne", "gt", "ge", "lt", "le").contains(LogicCatalog.text(node, "operator"))) issues.add(new Issue("logic.operator", id, false));
        }
        Map<String, List<String>> successors = new HashMap<>();
        Set<String> inputs = new HashSet<>(), outputs = new HashSet<>();
        for (var edge : graph.edges()) {
            var from = nodes.get(edge.from());
            var to = nodes.get(edge.to());
            if (from == null || to == null) { issues.add(new Issue("logic.missing_node", edge.to(), false));
                continue;
                }
            var fs = LogicCatalog.spec(from, graph);
            var ts = LogicCatalog.spec(to, graph);
            if (fs == null || ts == null) continue;
            var out = fs.outputs().get(edge.output());
            var in = ts.inputs().get(edge.input());
            if (out == null || in == null) issues.add(new Issue("logic.port", to.id(), false));
            else if (out != in) issues.add(new Issue("logic.type", to.id(), false));
            if (in != LogicType.EXECUTION && !inputs.add(to.id() + ":" + edge.input())) issues.add(new Issue("logic.input_duplicate", to.id(), false));
            if (out == LogicType.EXECUTION && !outputs.add(from.id() + ":" + edge.output())) issues.add(new Issue("logic.output_duplicate", from.id(), false));
            successors.computeIfAbsent(from.id(), ignored -> new ArrayList<>()).add(to.id());
        }
        for (var node : graph.nodes()) {
            var spec = LogicCatalog.spec(node, graph);
            if (spec == null) continue;
            if (spec.category().equals("event") && !outputs.contains(node.id() + ":next")) issues.add(new Issue("logic.disconnected", node.id(), true));
            spec.inputs().forEach((port, type) -> {
                if (type == LogicType.EXECUTION) return;
                var value = node.settings().get(port);
                if (value != null && value.type() != type) issues.add(new Issue("logic.type", node.id(), false));
                if (value == null && !inputs.contains(node.id() + ":" + port)) issues.add(new Issue("logic.input_missing", node.id(), false));
            });
        }
        var colors = new HashMap<String, Integer>();
        for (String id : nodes.keySet()) if (depth(id, successors, colors, new HashSet<>()) > 32) { issues.add(new Issue("logic.cycle_or_depth", id, false));
            break;
            }
        Set<String> reachable = new HashSet<>();
        nodes.values().stream().filter(n -> n.type().startsWith("on_")).forEach(n -> reach(n.id(), successors, reachable));
        // Data sources are reachable backwards from consumers, rather than through execution entry points.
        boolean changed;
        do { changed = false;
            for (var edge : graph.edges()) if (reachable.contains(edge.to())) changed |= reachable.add(edge.from());
        } while (changed);
        nodes.values().stream().filter(n -> !reachable.contains(n.id())).forEach(n -> issues.add(new Issue("logic.unreachable", n.id(), true)));
        return List.copyOf(issues);
    }
    private int depth(String id, Map<String, List<String>> next, Map<String, Integer> lengths, Set<String> visiting) {
        if (lengths.containsKey(id)) return lengths.get(id);
        if (!visiting.add(id)) return 129;
        int value = 0;
        for (String child : next.getOrDefault(id, List.of())) value = Math.max(value, 1 + depth(child, next, lengths, visiting));
        visiting.remove(id);
        lengths.put(id, value);
        return value;
    }
    private void reach(String id, Map<String, List<String>> next, Set<String> reached) {
        if (!reached.add(id)) return;
        for (String child : next.getOrDefault(id, List.of())) reach(child, next, reached);
    }
}
