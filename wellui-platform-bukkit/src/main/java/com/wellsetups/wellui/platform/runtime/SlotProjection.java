package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.LayoutEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic 9x6 projection. Overlapping interactive items use last-child-wins stacking. */
public final class SlotProjection {
    public record Result(Map<Integer, UiComponent> slots, List<String> collisions) { }

    public Result project(UiDocument document) {
        Map<Integer, UiComponent> slots = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();
        // Preserve document aspect ratio; normalize x and y independently only when choosing cells.
        for (var placed : new LayoutEngine().layout(document, document.width(), document.height())) {
            if (placed.component() instanceof PanelComponent) continue;
            int col = (int) Math.floor((placed.x() + placed.width() / 2) * 9 / document.width());
            int row = (int) Math.floor((placed.y() + placed.height() / 2) * 6 / document.height());
            if (col < 0 || col > 8 || row < 0 || row > 5) {
                collisions.add(placed.component().id());
                continue;
            }
            if (slots.put(row * 9 + col, placed.component()) != null) collisions.add(placed.component().id());
        }
        return new Result(Map.copyOf(slots), List.copyOf(collisions));
    }
}
