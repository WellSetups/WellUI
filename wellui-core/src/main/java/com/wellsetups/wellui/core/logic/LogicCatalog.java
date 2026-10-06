package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.wellsetups.wellui.api.logic.LogicType.EXECUTION;
import static com.wellsetups.wellui.api.logic.LogicType.STRING;
import static com.wellsetups.wellui.api.logic.LogicType.NUMBER;
import static com.wellsetups.wellui.api.logic.LogicType.BOOLEAN;

/** Closed built-in catalog. Future extensions belong at this boundary, never in script evaluation. */
public final class LogicCatalog {
    public record Spec(String category, Map<String, LogicType> inputs, Map<String, LogicType> outputs, Map<String, LogicType> settings) { }
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    static {
        for (String type : List.of("on_open", "on_close")) add(type, "event", Map.of(), Map.of("next", EXECUTION), Map.of());
        for (String type : List.of("on_click", "on_hover_enter", "on_hover_exit")) add(type, "event", Map.of(), Map.of("next", EXECUTION), Map.of("component", STRING));
        add("on_animation_complete", "event", Map.of(), Map.of("next", EXECUTION), Map.of("animation", STRING));
        for (String type : List.of("play_animation", "pause_animation", "resume_animation", "stop_animation", "restart_animation"))
            add(type, "action", Map.of("in", EXECUTION), Map.of("next", EXECUTION), Map.of("animation", STRING));
        add("branch", "flow", Map.of("in", EXECUTION, "condition", BOOLEAN), Map.of("true", EXECUTION, "false", EXECUTION), Map.of());
        add("sequence", "flow", Map.of("in", EXECUTION), Map.of("first", EXECUTION, "second", EXECUTION, "third", EXECUTION), Map.of());
        add("delay", "flow", Map.of("in", EXECUTION), Map.of("next", EXECUTION), Map.of("ticks", NUMBER));
        add("stop", "flow", Map.of("in", EXECUTION), Map.of(), Map.of());
        add("has_permission", "condition", Map.of(), Map.of("value", BOOLEAN), Map.of("permission", STRING));
        for (String type : List.of("is_sneaking", "is_flying")) add(type, "condition", Map.of(), Map.of("value", BOOLEAN), Map.of());
        for (String type : List.of("placeholder_equals", "placeholder_contains")) add(type, "condition", Map.of("compare", STRING), Map.of("value", BOOLEAN), Map.of("placeholder", STRING));
        add("placeholder_numeric", "condition", Map.of("compare", NUMBER), Map.of("value", BOOLEAN), Map.of("placeholder", STRING, "operator", STRING));
        add("send_message", "action", Map.of("in", EXECUTION, "value", STRING), Map.of("next", EXECUTION), Map.of());
        add("player_command", "action", Map.of("in", EXECUTION, "value", STRING), Map.of("next", EXECUTION), Map.of());
        add("close_ui", "action", Map.of("in", EXECUTION), Map.of(), Map.of());
        add("open_ui", "action", Map.of("in", EXECUTION), Map.of(), Map.of("project", STRING));
        add("set_variable", "action", Map.of("in", EXECUTION, "value", STRING), Map.of("next", EXECUTION), Map.of("variable", STRING));
        add("add_number", "action", Map.of("in", EXECUTION, "value", NUMBER), Map.of("next", EXECUTION), Map.of("variable", STRING));
        add("toggle_boolean", "action", Map.of("in", EXECUTION), Map.of("next", EXECUTION), Map.of("variable", STRING));
        add("set_visible", "action", Map.of("in", EXECUTION, "value", BOOLEAN), Map.of("next", EXECUTION), Map.of("component", STRING));
        add("set_text", "action", Map.of("in", EXECUTION, "value", STRING), Map.of("next", EXECUTION), Map.of("component", STRING));
        add("set_opacity", "action", Map.of("in", EXECUTION, "value", NUMBER), Map.of("next", EXECUTION), Map.of("component", STRING));
        for (LogicType type : List.of(STRING, NUMBER, BOOLEAN)) add("constant_" + type.name().toLowerCase(java.util.Locale.ROOT), "value", Map.of(), Map.of("value", type), Map.of("value", type));
        add("variable", "value", Map.of(), Map.of("value", STRING), Map.of("variable", STRING));
        add("placeholder", "value", Map.of(), Map.of("value", STRING), Map.of("placeholder", STRING));
        add("player_name", "value", Map.of(), Map.of("value", STRING), Map.of());
        for (String type : List.of("on_value_change", "on_focus", "on_blur", "on_submit", "on_scroll"))
            add(type, "event", Map.of(), Map.of("next", EXECUTION), Map.of("component", STRING));
        for (String type : List.of("input_value", "selected_value", "validation_message")) add(type, "value", Map.of(), Map.of("value", STRING), Map.of("component", STRING));
        for (String type : List.of("checkbox_value", "toggle_value", "form_valid")) add(type, "value", Map.of(), Map.of("value", BOOLEAN), Map.of("component", STRING));
        for (String type : List.of("slider_value", "scroll_position")) add(type, "value", Map.of(), Map.of("value", NUMBER), Map.of("component", STRING));
        add("set_value", "action", Map.of("in", EXECUTION, "value", STRING), Map.of("next", EXECUTION), Map.of("component", STRING, "valueType", STRING));
        add("set_disabled", "action", Map.of("in", EXECUTION, "value", BOOLEAN), Map.of("next", EXECUTION), Map.of("component", STRING));
        add("scroll_to", "action", Map.of("in", EXECUTION, "value", NUMBER), Map.of("next", EXECUTION), Map.of("component", STRING));
        for (String type : List.of("focus_component", "blur_component", "reset_form", "submit_form", "clear_input"))
            add(type, "action", Map.of("in", EXECUTION), Map.of("next", EXECUTION), Map.of("component", STRING));
        for (String type : List.of("focus_next", "focus_previous")) add(type, "action", Map.of("in", EXECUTION), Map.of("next", EXECUTION), Map.of());
    }
    private LogicCatalog() { }
    private static void add(String id, String category, Map<String, LogicType> in, Map<String, LogicType> out, Map<String, LogicType> settings) {
        SPECS.put(id, new Spec(category, Map.copyOf(in), Map.copyOf(out), Map.copyOf(settings)));
    }
    public static Map<String, Spec> all() { return Map.copyOf(SPECS); }
    public static Spec spec(UiLogicGraph.Node node, UiLogicGraph graph) {
        Spec spec = SPECS.get(node.type());
        if (spec == null) return null;
        if (node.type().equals("set_value")) {
            LogicType type;
            try { type = LogicType.valueOf(text(node, "valueType")); }
            catch (IllegalArgumentException invalid) { type = STRING; }
            if (type == EXECUTION) type = STRING;
            return new Spec(spec.category(), Map.of("in", EXECUTION, "value", type), spec.outputs(), spec.settings());
        }
        if (node.type().equals("variable") || node.type().equals("set_variable")) {
            String name = text(node, "variable");
            LogicType type = graph.variables().stream().filter(v -> v.name().equals(name)).map(UiLogicGraph.Variable::type).findFirst().orElse(STRING);
            return new Spec(spec.category(), node.type().equals("set_variable") ? Map.of("in", EXECUTION, "value", type) : spec.inputs(),
                    node.type().equals("variable") ? Map.of("value", type) : spec.outputs(), spec.settings());
        }
        return spec;
    }
    public static String text(UiLogicGraph.Node node, String key) {
        LogicValue value = node.settings().get(key);
        return value != null && value.type() == STRING ? value.string() : "";
    }
}
