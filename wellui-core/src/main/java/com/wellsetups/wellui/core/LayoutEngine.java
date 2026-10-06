package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.Layout;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.util.ArrayList;
import java.util.List;

/** Pure anchored layout, uniformly letterboxed into the requested viewport. */
public final class LayoutEngine {
    public record PlacedComponent(UiComponent component, double x, double y, double width, double height) { }
    /** Internal validation helper, after structural bounds have been checked; avoids recursive validation. */
    public List<PlacedComponent> reference(UiDocument document) {
        var result = new ArrayList<PlacedComponent>();
        place(document.children(), 0, 0, document.width(), document.height(), 1, result);
        return List.copyOf(result);
    }

    public List<PlacedComponent> layout(UiDocument document, double width, double height) {
        if (!new DocumentValidator().validate(document).isEmpty() || !Double.isFinite(width)
                || !Double.isFinite(height) || width <= 0 || height <= 0) throw new IllegalArgumentException("validation.layout");
        double scale = Math.min(width / document.width(), height / document.height());
        List<PlacedComponent> result = new ArrayList<>();
        place(document.children(), (width - document.width() * scale) / 2, (height - document.height() * scale) / 2,
                document.width() * scale, document.height() * scale, scale, result);
        return List.copyOf(result);
    }

    private void place(List<UiComponent> children, double x, double y, double width, double height,
                       double scale, List<PlacedComponent> result) {
        for (UiComponent child : children) {
            Layout box = child.layout();
            double w = box.width() * scale;
            double h = box.height() * scale;
            double left = x + box.x() * scale;
            double top = y + box.y() * scale;
            if (box.anchor().endsWith("right")) left += width - w;
            if (box.anchor().startsWith("bottom")) top += height - h;
            if (box.anchor().equals("center")) {
                left += (width - w) / 2;
                top += (height - h) / 2;
            }
            result.add(new PlacedComponent(child, left, top, w, h));
            if (child instanceof PanelComponent panel) place(panel.children(), left, top, w, h, scale, result);
        }
    }
}
