package com.wellsetups.wellui.api.document;

/** Colors use #RRGGBB; opacity is in [0,1]; fontSize is in reference pixels. */
public record Style(String background, String color, double opacity, int fontSize) { }
