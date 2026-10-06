package com.wellsetups.wellui.core.control;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiControl;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.core.DocumentValidator.ValidationIssue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded public control schema, including combinations that prepared SCREEN cannot represent. */
public final class ControlValidator {
    private static final Set<String> FLAGS = Set.of("disabled", "readOnly", "required", "multiline");
    private static final Set<String> NUMBERS = Set.of("min", "max", "step", "minLength", "maxLength", "scrollSpeed");
    private static final Set<String> TEXT = Set.of("placeholder", "pattern", "message", "overflow", "axis");
    public List<ValidationIssue> validate(UiDocument document) {
        var issues = new ArrayList<ValidationIssue>();
        var nodes = new HashMap<String, UiComponent>();
        collect(document.children(), nodes);
        if (document.controls().size() > 64) issues.add(new ValidationIssue("control.limit", ""));
        document.controls().forEach((id, c) -> {
            try {
                var node = nodes.get(id);
                if (node == null || c.field() != (node instanceof ButtonComponent) || !c.field() && !(node instanceof PanelComponent)) fail("target");
                c.settings().forEach((key, value) -> {
                    if (key.equals("initial")) value.require(c.valueType());
                    else if (FLAGS.contains(key)) value.require(LogicType.BOOLEAN);
                    else if (NUMBERS.contains(key)) value.require(LogicType.NUMBER);
                    else if (TEXT.contains(key)) value.require(LogicType.STRING);
                    else fail("setting");
                });
                if (c.flag("multiline")) fail("multiline_unsupported");
                if (c.text("placeholder", "").length() > 128 || c.text("message", "").length() > 128) fail("text_limit");
                double minLength = c.number("minLength", 0), maxLength = c.number("maxLength", 128);
                if (minLength < 0 || maxLength < 1 || maxLength > 512 || minLength > maxLength || minLength != Math.rint(minLength) || maxLength != Math.rint(maxLength)) fail("length");
                if (!safePattern(c.text("pattern", ""))) fail("pattern");
                double min = c.number("min", 0), max = c.number("max", 100), step = c.number("step", 1);
                if (min > max || Math.abs(min) > 1e6 || Math.abs(max) > 1e6 || step <= 0 || step > 1e6) fail("range");
                if (c.kind() == UiControl.Kind.SLIDER && min == max) fail("range");
                if (c.options().size() > 32 || c.kind() != UiControl.Kind.SELECT && !c.options().isEmpty()) fail("options");
                var values = new HashSet<String>();
                for (var option : c.options()) {
                    if (option.value().isEmpty() || option.value().length() > 128 || option.label().isBlank() || option.label().length() > 128
                            || !values.add(option.value()) || option.label().codePoints().anyMatch(Character::isISOControl)
                            || option.value().codePoints().anyMatch(Character::isISOControl)) fail("options");
                }
                if (c.kind() == UiControl.Kind.SELECT && c.options().isEmpty()) fail("options");
                if (c.kind() == UiControl.Kind.SELECT && (node.layout().width() > document.width() || node.layout().width() < 40 || node.layout().height() < 20 || document.height() < 60)) fail("select_bounds");
                ControlState.normalize(c, c.initial());
                if (c.kind() == UiControl.Kind.SCROLL) viewport(document, id, (PanelComponent) node, c);
            } catch (RuntimeException invalid) {
                issues.add(new ValidationIssue(invalid.getMessage() != null && invalid.getMessage().startsWith("control.") ? invalid.getMessage() : "control.settings", id));
            }
        });
        return List.copyOf(issues);
    }
    /** One character class plus bounded repetition, no groups, alternation, lookaround or backreferences. */
    public static boolean safePattern(String pattern) {
        if (pattern.isEmpty()) return true;
        if (pattern.length() > 96 || !pattern.matches("\\[[A-Za-z0-9 _.,@+\\-]+\\]\\{[0-9]{1,3},[0-9]{1,3}\\}")) return false;
        try {
            int start = pattern.indexOf('{'), comma = pattern.indexOf(',', start);
            int min = Integer.parseInt(pattern.substring(start + 1, comma)), max = Integer.parseInt(pattern.substring(comma + 1, pattern.length() - 1));
            if (min > max || max > 512) return false;
            java.util.regex.Pattern.compile(pattern);
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    private static void viewport(UiDocument document, String id, PanelComponent panel, UiControl control) {
        UiControl.Overflow.valueOf(control.text("overflow", "SCROLL"));
        if (!Set.of("x", "y").contains(control.text("axis", "y"))) fail("axis");
        double speed = control.number("scrollSpeed", 40);
        if (speed < 1 || speed > 4096) fail("scroll_speed");
        var descendants = new HashMap<String, UiComponent>();
        collect(panel.children(), descendants);
        if (descendants.size() > 32) fail("scroll_children");
        if (control.text("overflow", "SCROLL").equals("VISIBLE")) return;
        if (descendants.keySet().stream().anyMatch(document.controls()::containsKey)) fail("nested_viewport_control");
        for (var animation : document.animations().items()) for (var track : animation.tracks()) {
            if (descendants.containsKey(track.component())) fail("viewport_child_animation");
        }
        for (var n : document.logic().nodes()) {
            var component = n.settings().get("component");
            if (component != null && descendants.containsKey(component.value()) && Set.of("set_visible", "set_text", "set_opacity").contains(n.type())) fail("viewport_child_dynamic");
        }
        var metrics = Viewport.metrics(document, id);
        if (metrics.steps() > 32) fail("scroll_steps");
    }
    private static void collect(List<UiComponent> nodes, Map<String, UiComponent> target) {
        for (var node : nodes) {
            target.put(node.id(), node);
            if (node instanceof PanelComponent panel) collect(panel.children(), target);
        }
    }
    private static void fail(String code) { throw new IllegalArgumentException("control." + code); }
}
