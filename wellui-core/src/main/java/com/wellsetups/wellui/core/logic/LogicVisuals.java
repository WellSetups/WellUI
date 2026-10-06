package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.document.UiDocument;
import java.util.Set;
import java.util.stream.Collectors;

/** Resource requirements are derived from the same immutable revision as its graph. */
public final class LogicVisuals {
    private LogicVisuals() { }
    public static boolean dynamic(UiDocument document) {
        return !document.controls().isEmpty() || document.logic().nodes().stream().anyMatch(n -> Set.of("set_visible", "set_text", "set_opacity").contains(n.type()));
    }
    public static Set<String> text(UiDocument document) {
        var result = document.logic().nodes().stream().filter(n -> n.type().equals("set_text"))
                .map(n -> LogicCatalog.text(n, "component")).collect(Collectors.toSet());
        document.controls().forEach((id, c) -> { if (c.field()) result.add(id); });
        return Set.copyOf(result);
    }
}
