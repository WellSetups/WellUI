package com.wellsetups.wellui.core.control;

import com.wellsetups.wellui.api.document.UiControl;
import com.wellsetups.wellui.core.LayoutEngine.PlacedComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/** Deploy-only artwork. Common state treatment; runtime only chooses prepared models. */
public final class ControlPaint {
    private ControlPaint() { }
    public static double knobSize(PlacedComponent p) { return Math.min(p.height() * .35, p.width() * .08); }
    public static void paint(Graphics2D g, PlacedComponent p, UiControl control, UiControl.State state, boolean checked) {
        double x = p.x(), y = p.y(), w = p.width(), h = p.height();
        Color foreground = Color.decode(p.component().style().color());
        g.setColor(foreground);
        double size = Math.min(h * .45, w * .15), inset = Math.min(8, w * .03);
        switch (control.kind()) {
            case CHECKBOX -> {
                var box = new Rectangle2D.Double(x + inset, y + (h - size) / 2, size, size);
                g.setStroke(new BasicStroke(2));
                if (checked) g.fill(box);
                else g.draw(box);
            }
            case TOGGLE -> {
                g.setColor(checked ? foreground : Color.GRAY);
                g.fill(new RoundRectangle2D.Double(x + inset, y + (h - size) / 2, size * 2, size, size, size));
                g.setColor(Color.decode(p.component().style().background()));
                g.fill(new Ellipse2D.Double(x + inset + (checked ? size : 0) + 2, y + (h - size) / 2 + 2, Math.max(1, size - 4), Math.max(1, size - 4)));
            }
            case SLIDER -> g.fill(new Rectangle2D.Double(x + knobSize(p) / 2, y + h * .8, w - knobSize(p), Math.max(2, h * .04)));
            case SELECT -> {
                int right = (int) (x + w - inset), top = (int) (y + h / 2);
                g.fillPolygon(new int[]{right - 10, right, right - 5}, new int[]{top - 3, top - 3, top + 3}, 3);
            }
            default -> { }
        }
        if (state == UiControl.State.DISABLED) {
            g.setColor(new Color(0, 0, 0, 130));
            g.fill(new Rectangle2D.Double(x, y, w, h));
        } else if (state != UiControl.State.NORMAL) {
            g.setColor(state == UiControl.State.INVALID ? new Color(0xFF6666) : state == UiControl.State.ACTIVE ? new Color(0x52CEAA) : new Color(0xFFDC69));
            g.setStroke(new BasicStroke(2));
            g.draw(new Rectangle2D.Double(x + 1, y + 1, Math.max(0, w - 2), Math.max(0, h - 2)));
        }
    }
}
