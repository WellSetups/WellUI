package com.wellsetups.wellui.api.document;

/** Plain text, optionally interpreted by an explicitly enabled placeholder integration. */
public record TextComponent(String id, Layout layout, Style style, String text) implements UiComponent { }
