package com.wellsetups.wellui.api.animation;

public enum UiAnimatedProperty {
    TRANSLATION_X(0), TRANSLATION_Y(0), SCALE_X(1), SCALE_Y(1), ROTATION_Z(0), OPACITY(1), VISIBILITY(1);
    private final double baseline;
    UiAnimatedProperty(double baseline) { this.baseline = baseline; }
    public double baseline() { return baseline; }
    public boolean transform() { return this != OPACITY && this != VISIBILITY; }
    public boolean valid(double value) {
        if (!Double.isFinite(value)) return false;
        return switch (this) {
            case TRANSLATION_X, TRANSLATION_Y -> Math.abs(value) <= 16384;
            case SCALE_X, SCALE_Y -> value >= .01 && value <= 16;
            case ROTATION_Z -> Math.abs(value) <= 3600;
            case OPACITY -> value >= 0 && value <= 1;
            case VISIBILITY -> value == 0 || value == 1;
        };
    }
}
