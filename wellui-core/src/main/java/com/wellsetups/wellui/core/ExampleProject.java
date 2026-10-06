package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.Layout;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.Style;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiAction;
import com.wellsetups.wellui.api.document.UiDocument;
import java.util.List;
import java.util.ResourceBundle;

/** Original starter document with translatable content and no external assets. */
public final class ExampleProject {
    private ExampleProject() { }

    public static UiDocument create() {
        var text = ResourceBundle.getBundle("example");
        var ink = new Style("#192336", "#eef4ff", 1, 28);
        var accent = new Style("#b8f36a", "#162719", 1, 18);
        return new UiDocument(1, "example", text.getString("name"), 900, 600, List.of(
                new PanelComponent("welcome", new Layout(60, 50, 780, 500, "top_left"),
                        new Style("#192336", "#ffffff", 1, 16), List.of(
                        new TextComponent("title", new Layout(40, 45, 500, 65, "top_left"), ink, text.getString("title")),
                        new TextComponent("subtitle", new Layout(40, 135, 620, 75, "top_left"),
                                new Style("#192336", "#a7b8d5", 1, 19), text.getString("subtitle")),
                        new ButtonComponent("hello", new Layout(40, 280, 240, 60, "top_left"), accent,
                                text.getString("hello"), new UiAction("send_message", text.getString("message"))),
                        new ButtonComponent("close", new Layout(430, 280, 240, 60, "top_left"), ink,
                                text.getString("close"), new UiAction("close_ui", ""))))));
    }
}
