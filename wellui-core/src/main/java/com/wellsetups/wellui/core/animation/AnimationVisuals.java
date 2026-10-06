package com.wellsetups.wellui.core.animation;

import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.ButtonComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AnimationVisuals {
    private AnimationVisuals() { }
    public static Set<String> independent(UiDocument document) {
        var roots = new HashSet<String>();
        roots.addAll(document.controls().keySet());
        document.animations().items().forEach(a -> a.tracks().forEach(t -> roots.add(t.component())));
        document.logic().nodes().stream().filter(n -> Set.of("set_visible", "set_text", "set_opacity").contains(n.type()))
                .forEach(n -> roots.add(com.wellsetups.wellui.core.logic.LogicCatalog.text(n, "component")));
        var result = new HashSet<String>();
        collect(document.children(), roots, result, false);
        return Set.copyOf(result);
    }
    private static void collect(List<UiComponent> nodes, Set<String> roots, Set<String> result, boolean inherited) {
        for (var n : nodes) {
            boolean affected = inherited || roots.contains(n.id());
            if (affected || n instanceof ButtonComponent) result.add(n.id());
            if (n instanceof PanelComponent p) collect(p.children(), roots, result, affected);
        }
    }
    public static boolean transforms(UiDocument document) {
        return document.animations().items().stream().flatMap(a -> a.tracks().stream()).anyMatch(t -> t.property().transform());
    }
}
