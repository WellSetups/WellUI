package com.wellsetups.wellui.api.logic;

/** Canonical typed scalar. The textual payload is never implicitly coerced across types. */
public record LogicValue(LogicType type, String value) {
    public LogicValue {
        if (type == null || value == null || type == LogicType.EXECUTION || value.length() > 2048
                || value.codePoints().anyMatch(c -> c == 0 || Character.isISOControl(c) && c != '\n' && c != '\t')) throw new IllegalArgumentException("logic.value");
        if (type == LogicType.NUMBER) {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || Math.abs(number) > 1e12) throw new IllegalArgumentException("logic.number");
            value = Double.toString(number);
        }
        if (type == LogicType.BOOLEAN && !value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("logic.boolean");
    }
    public static LogicValue string(String value) { return new LogicValue(LogicType.STRING, value); }
    public static LogicValue number(double value) { return new LogicValue(LogicType.NUMBER, Double.toString(value)); }
    public static LogicValue bool(boolean value) { return new LogicValue(LogicType.BOOLEAN, Boolean.toString(value)); }
    public double number() { require(LogicType.NUMBER);
        return Double.parseDouble(value);
        }
    public boolean bool() { require(LogicType.BOOLEAN);
        return Boolean.parseBoolean(value);
        }
    public String string() { require(LogicType.STRING);
        return value;
        }
    public void require(LogicType expected) { if (type != expected) throw new IllegalArgumentException("logic.type"); }
}
