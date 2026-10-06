package com.wellsetups.wellui.api.document;

import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.api.logic.LogicValue;
import java.util.List;
import java.util.Map;

/** Immutable optional behavior for a button (field) or panel (form/viewport). Values are session-scoped. */
public record UiControl(Kind kind, Map<String, LogicValue> settings, List<Option> options) {
    public enum Kind { INPUT, CHECKBOX, TOGGLE, SLIDER, SELECT, FORM, SCROLL }
    public enum State { NORMAL, HOVER, FOCUSED, ACTIVE, DISABLED, INVALID }
    public enum Overflow { VISIBLE, HIDDEN, SCROLL }
    public record Option(String value, String label, boolean disabled) { }
    public UiControl {
        java.util.Objects.requireNonNull(kind);
        settings = Map.copyOf(settings);
        options = List.copyOf(options);
    }
    public UiControl(Kind kind) { this(kind, Map.of(), List.of()); }
    public String text(String key, String fallback) { return settings.containsKey(key) ? settings.get(key).string() : fallback; }
    public double number(String key, double fallback) { return settings.containsKey(key) ? settings.get(key).number() : fallback; }
    public boolean flag(String key) { return settings.containsKey(key) && settings.get(key).bool(); }
    public boolean field() { return kind != Kind.FORM && kind != Kind.SCROLL; }
    public LogicType valueType() {
        return switch (kind) {
            case CHECKBOX, TOGGLE, FORM -> LogicType.BOOLEAN;
            case SLIDER, SCROLL -> LogicType.NUMBER;
            default -> LogicType.STRING;
        };
    }
    public LogicValue initial() {
        return settings.getOrDefault("initial", switch (valueType()) {
            case BOOLEAN -> LogicValue.bool(false);
            case NUMBER -> LogicValue.number(number("min", 0));
            default -> LogicValue.string("");
        });
    }
}
