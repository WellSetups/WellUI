package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Static proof-of-concept raster. Never called on a player or HTTP request thread. */
public final class HudRasterizer {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 128;
    private final AssetStore assets;
    public HudRasterizer(AssetStore assets) { this.assets = assets; }

    public byte[] render(UiDocument document) throws IOException {
        var placements = new LayoutEngine().layout(document, WIDTH, HEIGHT);
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D root = canvas.createGraphics();
        Map<String, Double> opacity = new HashMap<>();
        opacity(document.children(), 1, opacity);
        double scale = Math.min(WIDTH / document.width(), HEIGHT / document.height());
        try {
            root.setColor(new Color(0x101824));
            root.fillRect(0, 0, WIDTH, HEIGHT);
            root.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            root.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (var placed : placements) {
                UiComponent component = placed.component();
                Graphics2D graphics = (Graphics2D) root.create();
                try {
                    var box = new Rectangle2D.Double(placed.x(), placed.y(), placed.width(), placed.height());
                    graphics.clip(box);
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity.get(component.id()).floatValue()));
                    if (component instanceof PanelComponent || component instanceof ButtonComponent || component instanceof ImageComponent) {
                        graphics.setColor(Color.decode(component.style().background()));
                        graphics.fill(box);
                    }
                    if (component instanceof ImageComponent image) {
                        BufferedImage source = ImageIO.read(new ByteArrayInputStream(assets.read(image.asset())));
                        if (source == null) throw new IOException("asset.png");
                        double fit = Math.min(placed.width() / source.getWidth(), placed.height() / source.getHeight());
                        var transform = java.awt.geom.AffineTransform.getTranslateInstance(
                                placed.x() + (placed.width() - source.getWidth() * fit) / 2,
                                placed.y() + (placed.height() - source.getHeight() * fit) / 2);
                        transform.scale(fit, fit);
                        graphics.drawImage(source, transform, null);
                    }
                    String label = component instanceof TextComponent text ? text.text()
                            : component instanceof ButtonComponent button ? button.text() : null;
                    if (label != null) {
                        graphics.setColor(Color.decode(component.style().color()));
                        int fontSize = (int) Math.max(1, Math.min(512, Math.round(component.style().fontSize() * scale)));
                        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
                        String[] lines = label.split("\n", -1);
                        float y = (float) placed.y() + graphics.getFontMetrics().getAscent();
                        if (component instanceof ButtonComponent) y += (float) ((placed.height() - lines.length * graphics.getFontMetrics().getHeight()) / 2);
                        for (String line : lines) {
                            float x = (float) placed.x();
                            if (component instanceof ButtonComponent) x += (float) ((placed.width() - graphics.getFontMetrics().stringWidth(line)) / 2);
                            graphics.drawString(line, x, y);
                            y += graphics.getFontMetrics().getHeight();
                        }
                    }
                } finally { graphics.dispose(); }
            }
        } finally { root.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", output)) throw new IOException("asset.encoder");
        return output.toByteArray();
    }
    private void opacity(List<UiComponent> children, double inherited, Map<String, Double> result) {
        for (UiComponent child : children) {
            double value = inherited * child.style().opacity();
            result.put(child.id(), value);
            if (child instanceof PanelComponent panel) opacity(panel.children(), value, result);
        }
    }
}
