package com.wellsetups.wellui.api.document;

/** A clickable label with one server-validated action. */
public record ButtonComponent(String id, Layout layout, Style style, String text, UiAction action)
        implements UiComponent { }
