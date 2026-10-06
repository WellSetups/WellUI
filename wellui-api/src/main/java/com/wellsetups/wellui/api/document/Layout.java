package com.wellsetups.wellui.api.document;

/**
 * A rectangle positioned relative to an anchor in its parent.
 * Positive x/y offsets always move right/down. Center/right/bottom anchors also align
 * the corresponding component edge, so (0,0) at bottom_right is flush with that corner.
 */
public record Layout(double x, double y, double width, double height, String anchor) { }
