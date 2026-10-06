package com.wellsetups.wellui.api.document;

import java.util.List;
import java.util.Objects;

/** Immutable, platform-independent UI project in a reference-coordinate canvas. */
public record UiDocument(int schemaVersion, String id, String name, double width, double height,
                         List<UiComponent> children, com.wellsetups.wellui.api.logic.UiLogicGraph logic,
                         com.wellsetups.wellui.api.animation.UiAnimations animations, java.util.Map<String, UiControl> controls) {
    public UiDocument(int schemaVersion, String id, String name, double width, double height, List<UiComponent> children,
                      com.wellsetups.wellui.api.logic.UiLogicGraph logic, com.wellsetups.wellui.api.animation.UiAnimations animations) {
        this(schemaVersion, id, name, width, height, children, logic, animations, java.util.Map.of());
    }
    public UiDocument(int schemaVersion, String id, String name, double width, double height, List<UiComponent> children) {
        this(schemaVersion, id, name, width, height, children, com.wellsetups.wellui.api.logic.UiLogicGraph.empty());
    }
    public UiDocument(int schemaVersion, String id, String name, double width, double height, List<UiComponent> children,
                      com.wellsetups.wellui.api.logic.UiLogicGraph logic) {
        this(schemaVersion, id, name, width, height, children, logic, com.wellsetups.wellui.api.animation.UiAnimations.empty());
    }
    /** Copies the component list so a published document cannot change underneath a renderer. */
    public UiDocument {
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        logic = Objects.requireNonNull(logic, "logic");
        animations = Objects.requireNonNull(animations, "animations");
        controls = java.util.Map.copyOf(controls);
    }
}
