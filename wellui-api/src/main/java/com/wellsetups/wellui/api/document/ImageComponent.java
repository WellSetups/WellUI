package com.wellsetups.wellui.api.document;

/** An uploaded image referenced by its lowercase SHA-256 identifier, never a file path or URL. */
public record ImageComponent(String id, Layout layout, Style style, String asset) implements UiComponent { }
