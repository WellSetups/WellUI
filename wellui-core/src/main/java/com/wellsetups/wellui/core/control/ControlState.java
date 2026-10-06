package com.wellsetups.wellui.core.control;

import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiControl;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.logic.LogicValue;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Owner-thread control state. No IO, platform APIs, persisted values, scripts or unbounded event queues. */
public final class ControlState implements AutoCloseable {
    public record Event(String type, String component) { }
    private final UiDocument document;
    private final Runnable changed;
    private final Map<String, LogicValue> values = new HashMap<>();
    private final Map<String, Boolean> disabled = new HashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();
    private final Set<String> invalid = new HashSet<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private Predicate<String> visible = id -> true;
    private String focused = "", active = "", overlay = "";
    private boolean closed;
    private java.util.function.Function<String, String> messages = ControlState::english;
    /** Platform supplies localized defaults; authored messages remain document content. */
    public void messages(java.util.function.Function<String, String> value) { messages = java.util.Objects.requireNonNull(value); }
    private static String english(String key) {
        return switch (key) {
            case "control.required" -> "Required";
            case "control.too_short" -> "Too short";
            case "control.too_long" -> "Too long";
            case "control.invalid_format" -> "Invalid format";
            case "control.out_of_range" -> "Out of range";
            default -> key;
        };
    }
    public ControlState(UiDocument document, Runnable changed) {
        this.document = document;
        this.changed = changed;
        collect(document.children(), "");
        document.controls().forEach((id, c) -> values.put(id, c.kind() == UiControl.Kind.SCROLL
                ? LogicValue.number(Viewport.metrics(document, id).offset(c.initial().number())) : normalize(c, c.initial())));
        var initialScene = new com.wellsetups.wellui.core.screen.ScreenScene(document);
        visible = id -> initialScene.opacity(id) > 0;
    }
    private void collect(List<UiComponent> nodes, String parent) {
        for (var node : nodes) {
            parents.put(node.id(), parent);
            if (node instanceof PanelComponent panel) collect(panel.children(), node.id());
        }
    }
    public UiControl definition(String id) {
        var c = document.controls().get(id);
        if (c == null || closed) throw new IllegalArgumentException("control.target");
        return c;
    }
    public Map<String, LogicValue> values() { return Map.copyOf(values); }
    public LogicValue value(String id) {
        var c = definition(id);
        return c.kind() == UiControl.Kind.FORM ? LogicValue.bool(valid(id)) : values.get(id);
    }
    public static LogicValue normalize(UiControl c, LogicValue value) {
        value.require(c.valueType());
        return switch (c.kind()) {
            case INPUT -> {
                if (value.string().codePoints().anyMatch(Character::isISOControl) || value.string().length() > c.number("maxLength", 128)) throw new IllegalArgumentException("control.input_length");
                yield value;
            }
            case SELECT -> {
                if (!value.string().isEmpty() && c.options().stream().noneMatch(o -> o.value().equals(value.string()) && !o.disabled())) throw new IllegalArgumentException("control.option");
                yield value;
            }
            case SLIDER -> {
                double min = c.number("min", 0), max = c.number("max", 100), step = c.number("step", 1);
                yield LogicValue.number(Math.max(min, Math.min(max, min + Math.round((Math.max(min, Math.min(max, value.number())) - min) / step) * step)));
            }
            default -> value;
        };
    }
    public boolean set(String id, LogicValue requested) {
        var c = definition(id);
        if (c.kind() == UiControl.Kind.FORM) throw new IllegalArgumentException("control.form_value");
        var value = c.kind() == UiControl.Kind.SCROLL ? LogicValue.number(Viewport.metrics(document, id).offset(requested.number())) : normalize(c, requested);
        if (value.equals(values.get(id))) return false;
        values.put(id, value);
        if (invalid.contains(id) && error(id).isEmpty()) invalid.remove(id);
        changed.run();
        event(c.kind() == UiControl.Kind.SCROLL ? "on_scroll" : "on_value_change", id);
        return true;
    }
    public boolean edit(String id, LogicValue value) {
        return usable(id) && !definition(id).flag("readOnly") && set(id, value);
    }
    public boolean disabled(String id) {
        for (String current = id; !current.isEmpty(); current = parents.getOrDefault(current, "")) {
            var c = document.controls().get(current);
            if (disabled.getOrDefault(current, c != null && c.flag("disabled"))) return true;
        }
        return false;
    }
    public void disabled(String id, boolean value) {
        definition(id);
        disabled.put(id, value);
        changed.run();
        reconcile(visible);
    }
    public boolean usable(String id) { return !closed && document.controls().containsKey(id) && visible.test(id) && !disabled(id); }
    public boolean focus(String id) {
        if (id.isEmpty()) { blur();
            return true;
        }
        if (!usable(id) || !definition(id).field()) return false;
        if (focused.equals(id)) return true;
        blur();
        focused = id;
        changed.run();
        event("on_focus", id);
        return true;
    }
    public String focused() { return focused; }
    public void blur() {
        String old = focused;
        focused = "";
        active = "";
        overlay = "";
        if (!old.isEmpty()) { changed.run();
            event("on_blur", old);
        }
    }
    public void focusNext(boolean previous) {
        var ids = parents.keySet().stream().filter(id -> usable(id) && definition(id).field()).toList();
        if (ids.isEmpty()) { blur();
            return;
        }
        int index = ids.indexOf(focused);
        focus(ids.get(index < 0 ? previous ? ids.size() - 1 : 0 : Math.floorMod(index + (previous ? -1 : 1), ids.size())));
    }
    public void reconcile(Predicate<String> predicate) {
        visible = predicate;
        if (!focused.isEmpty() && !usable(focused)) blur();
        if (!active.isEmpty() && !usable(active)) active("");
        if (!overlay.isEmpty() && !usable(overlay)) overlay("");
    }
    public String active() { return active; }
    public void active(String id) {
        if (!id.isEmpty() && !usable(id)) return;
        if (!active.equals(id)) { active = id;
            changed.run();
        }
    }
    public String overlay() { return overlay; }
    public void overlay(String id) {
        if (!id.isEmpty() && !focus(id)) return;
        if (!overlay.equals(id)) { overlay = id;
            changed.run();
        }
    }
    public UiControl.State state(String id, String hovered) {
        if (disabled(id)) return UiControl.State.DISABLED;
        if (invalid.contains(id)) return UiControl.State.INVALID;
        if (active.equals(id) || overlay.equals(id)) return UiControl.State.ACTIVE;
        if (focused.equals(id)) return UiControl.State.FOCUSED;
        return id.equals(hovered) ? UiControl.State.HOVER : UiControl.State.NORMAL;
    }
    public double fraction(String id) {
        var c = definition(id);
        return (value(id).number() - c.number("min", 0)) / (c.number("max", 100) - c.number("min", 0));
    }
    public void drag(String id, double localX, double width) {
        var c = definition(id);
        if (c.kind() != UiControl.Kind.SLIDER || width <= 0) return;
        edit(id, LogicValue.number(c.number("min", 0) + Math.max(0, Math.min(1, localX / width)) * (c.number("max", 100) - c.number("min", 0))));
    }
    private boolean member(String id, String form) {
        for (String current = parents.getOrDefault(id, ""); !current.isEmpty(); current = parents.getOrDefault(current, "")) {
            var c = document.controls().get(current);
            if (c != null && c.kind() == UiControl.Kind.FORM) return current.equals(form);
        }
        return false;
    }
    public Map<String, LogicValue> formValues(String form) {
        if (definition(form).kind() != UiControl.Kind.FORM) throw new IllegalArgumentException("control.form");
        var result = new LinkedHashMap<String, LogicValue>();
        parents.keySet().stream().filter(id -> document.controls().containsKey(id) && definition(id).field() && member(id, form))
                .forEach(id -> result.put(id, value(id)));
        return Map.copyOf(result);
    }
    public String error(String id) {
        if (definition(id).kind() == UiControl.Kind.FORM) return formValues(id).keySet().stream().sorted().map(this::error).filter(message -> !message.isEmpty()).findFirst().orElse("");
        var c = definition(id);
        if (!c.field() || disabled(id)) return "";
        var value = values.get(id);
        boolean missing = switch (value.type()) {
            case STRING -> value.string().isBlank();
            case BOOLEAN -> !value.bool();
            default -> false;
        };
        String failure = c.flag("required") && missing ? "control.required" : "";
        if (c.kind() == UiControl.Kind.INPUT && !missing) {
            if (value.string().length() < c.number("minLength", 0)) failure = "control.too_short";
            if (value.string().length() > c.number("maxLength", 128)) failure = "control.too_long";
            String pattern = c.text("pattern", "");
            if (!pattern.isEmpty() && (!ControlValidator.safePattern(pattern) || !value.string().matches(pattern))) failure = "control.invalid_format";
        }
        if (c.kind() == UiControl.Kind.SLIDER && (value.number() < c.number("min", 0) || value.number() > c.number("max", 100))) failure = "control.out_of_range";
        String message = c.text("message", "");
        if (failure.isEmpty()) return "";
        String translated = messages.apply(failure);
        return message.isBlank() ? translated == null || translated.isBlank() ? english(failure) : translated : message;
    }
    public boolean valid(String form) { return formValues(form).keySet().stream().allMatch(id -> error(id).isEmpty()); }
    public boolean submit(String form) {
        boolean valid = true;
        for (String id : formValues(form).keySet()) {
            if (error(id).isEmpty()) invalid.remove(id);
            else { invalid.add(id);
                valid = false;
            }
        }
        changed.run();
        event("on_submit", form);
        return valid;
    }
    public void reset(String form) {
        for (String id : formValues(form).keySet()) {
            set(id, definition(id).initial());
            invalid.remove(id);
        }
        if (member(focused, form)) blur();
        changed.run();
    }
    public String label(String id, String label) {
        var c = document.controls().get(id);
        if (c == null || !c.field()) return label;
        var v = value(id);
        return switch (c.kind()) {
            case INPUT -> v.string().isEmpty() ? c.text("placeholder", label) : v.string();
            case SELECT -> c.options().stream().filter(o -> o.value().equals(v.string())).map(UiControl.Option::label).findFirst().orElse(c.text("placeholder", label));
            case SLIDER -> label + " " + java.math.BigDecimal.valueOf(v.number()).stripTrailingZeros().toPlainString();
            default -> label;
        };
    }
    private void event(String type, String id) {
        if (!closed && events.size() < 64) events.add(new Event(type, id));
    }
    public Event poll() { return events.poll(); }
    public void clearEvents() { events.clear(); }
    public void close() {
        closed = true;
        focused = active = overlay = "";
        values.clear();
        disabled.clear();
        invalid.clear();
        events.clear();
        parents.clear();
    }
}
