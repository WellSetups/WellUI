package com.wellsetups.wellui.api.document;

/** A typed scene node. Presentation backends explicitly report which properties they support. */
public sealed interface UiComponent permits PanelComponent, TextComponent, ImageComponent, ButtonComponent {
    /** Stable identifier, unique within the document. */
    String id();
    /** Position and size in the parent component's reference coordinates. */
    Layout layout();
    /** Visual appearance shared by the initial component types. */
    Style style();
}
