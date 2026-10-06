package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.screen.ScreenScene;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.AttributedString;
import javax.imageio.ImageIO;
import com.wellsetups.wellui.core.screen.RasterPolicy;
/** Baked rendering boundary. UiDocument remains independent of raster, text or future image-layer backends. */
public final class ScreenRasterizer {
    private final AssetStore assets;
    private final RasterPolicy policy;
    private com.wellsetups.wellui.api.document.UiControl.State controlState = com.wellsetups.wellui.api.document.UiControl.State.NORMAL;
    private boolean checked;
    private String viewportRoot;
    public ScreenRasterizer(AssetStore assets, RasterPolicy policy) {
        this.assets = assets;
        this.policy = policy;
    }
    public byte[] raster(UiDocument document, ScreenScene scene, String hover, RasterPolicy.Size size) throws IOException {
        return render(document, scene, hover, size, null, false, 1, 0, 0, size.width(), size.height(), null);
    }
    /** Cropped transparent component texture prepared only during deployment. */
    public byte[] layer(UiDocument document, ScreenScene scene, String id, boolean omitText, String hover,
                        RasterPolicy.Size size, double alpha, int x, int y, int width, int height) throws IOException {
        return render(document, scene, hover, size, id, omitText, alpha, x, y, width, height, null);
    }
    public byte[] group(UiDocument document, ScreenScene scene, RasterPolicy.Size size, java.util.Set<String> ids) throws IOException {
        return render(document, scene, null, size, null, false, 1, 0, 0, size.width(), size.height(), ids);
    }
    public byte[] control(UiDocument document, ScreenScene scene, String id, RasterPolicy.Size size, double alpha,
                          int x, int y, int width, int height, com.wellsetups.wellui.api.document.UiControl.State state, boolean value) throws IOException {
        controlState = state;
        checked = value;
        try { return layer(document, scene, id, true, null, size, alpha, x, y, width, height); }
        finally { controlState = com.wellsetups.wellui.api.document.UiControl.State.NORMAL;
            checked = false;
        }
    }
    public byte[] viewport(UiDocument document, String id, double offset, String hover, RasterPolicy.Size size,
                           double alpha, int x, int y, int width, int height) throws IOException {
        var scene = new ScreenScene(document, java.util.Map.of(), java.util.Map.of(id, offset));
        var ids = new java.util.HashSet<>(scene.viewports().get(id).children());
        ids.add(id);
        viewportRoot = id;
        try { return render(document, scene, hover, size, null, false, alpha, x, y, width, height, ids); }
        finally { viewportRoot = null; }
    }
    private byte[] render(UiDocument document, ScreenScene scene, String hover, RasterPolicy.Size size,
                          String only, boolean omitText, double alpha, int cropX, int cropY, int width, int height, java.util.Set<String> group) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D root = image.createGraphics();
        try {
            if (only == null && group == null) {
                root.setColor(new Color(0x0d1522));
                root.fillRect(0, 0, size.width(), size.height());
            }
            root.translate(-cropX, -cropY);
            // One uniform transform from authored coordinates to the final texture.
            root.scale(size.scale(), size.scale());
            root.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            root.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            root.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            root.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            root.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            root.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            for (var p : scene.placements()) {
                if (group != null && !group.contains(p.component().id())) continue;
                if (only != null && !only.equals(p.component().id()) || viewportRoot == null && only == null && scene.opacity(p.component().id()) == 0) continue;
                Graphics2D g = (Graphics2D) root.create();
                try {
                    var node = p.component();
                    String owner = scene.clipOwner(node.id());
                    if (owner != null) {
                        var clip = scene.viewports().get(owner).bounds();
                        g.clip(new Rectangle2D.Double(clip.x(), clip.y(), clip.width(), clip.height()));
                    }
                    var matrix = scene.transform(node.id());
                    var origin = matrix.point(p.x(), p.y());
                    g.translate(origin.x() - p.x(), origin.y() - p.y());
                    g.clip(new Rectangle2D.Double(p.x(), p.y(), p.width(), p.height()));
                    double effective = only == null ? scene.opacity(node.id()) : alpha;
                    if (viewportRoot != null) {
                        effective = scene.relativeOpacity(node.id(), viewportRoot) * alpha;
                    }
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, effective))));
                    if (!(node instanceof TextComponent) && !(node instanceof ImageComponent)) {
                        g.setColor(Color.decode(node.style().background()));
                        g.fill(new Rectangle2D.Double(p.x(), p.y(), p.width(), p.height()));
                    }
                    if (node instanceof ImageComponent imported) {
                        BufferedImage source = ImageIO.read(new ByteArrayInputStream(assets.read(imported.asset())));
                        if (source == null) throw new IOException("asset.png");
                        double fit = Math.min(p.width() / source.getWidth(), p.height() / source.getHeight());
                        var transform = java.awt.geom.AffineTransform.getTranslateInstance(p.x() + (p.width() - source.getWidth() * fit) / 2,
                                p.y() + (p.height() - source.getHeight() * fit) / 2);
                        transform.scale(fit, fit);
                        if (policy.images() == RasterPolicy.Interpolation.PIXELATED
                                || policy.images() == RasterPolicy.Interpolation.AUTO && pixelArt(source)) {
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        }
                        g.drawImage(source, transform, null);
                    }
                    var control = document.controls().get(node.id());
                    if (control != null && control.field()) com.wellsetups.wellui.core.control.ControlPaint.paint(g, p, control, controlState, checked);
                    String text = node instanceof TextComponent label ? label.text() : node instanceof ButtonComponent button ? button.text() : null;
                    if (text != null && !omitText) {
                        g.setFont(new Font(Font.SANS_SERIF, node instanceof ButtonComponent ? Font.BOLD : Font.PLAIN, node.style().fontSize()));
                        g.setColor(Color.decode(node.style().color()));
                        float y = (float) p.y();
                        for (String paragraph : text.split("\n", -1)) {
                            if (paragraph.isEmpty()) {
                                y += node.style().fontSize();
                                continue;
                            }
                            AttributedString attributed = new AttributedString(paragraph);
                            attributed.addAttribute(TextAttribute.FONT, g.getFont());
                            var iterator = attributed.getIterator();
                            var measurer = new LineBreakMeasurer(iterator, g.getFontRenderContext());
                            while (measurer.getPosition() < iterator.getEndIndex() && y < p.y() + p.height()) {
                                var line = measurer.nextLayout((float) Math.max(1, p.width()));
                                y += line.getAscent();
                                float x = (float) p.x();
                                if (node instanceof ButtonComponent) x += (float) (p.width() - line.getAdvance()) / 2;
                                line.draw(g, x, y);
                                y += line.getDescent() + line.getLeading();
                            }
                        }
                    }
                    if (node.id().equals(hover)) {
                        double border = Math.max(document.width(), document.height()) / 256;
                        g.setColor(new Color(0xffdc69));
                        g.setStroke(new BasicStroke((float) border));
                        g.draw(new Rectangle2D.Double(p.x() + border / 2, p.y() + border / 2, Math.max(0, p.width() - border), Math.max(0, p.height() - border)));
                    }
                } finally { g.dispose();
                    }
            }
            if (viewportRoot != null) {
                var metrics = scene.viewports().get(viewportRoot);
                if (metrics.maximum() > 0) {
                    var b = metrics.bounds();
                    root.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
                    root.setColor(new Color(0x667788));
                    double length = metrics.horizontal() ? b.width() : b.height();
                    double thumb = Math.max(8, length * length / (length + metrics.maximum()));
                    // The same translated child transform supplies the prepared offset.
                    String child = metrics.children().stream().findFirst().orElse(null);
                    double offset = 0;
                    if (child != null) {
                        var point = scene.transform(child).point(0, 0);
                        offset = metrics.horizontal() ? -point.x() : -point.y();
                    }
                    double position = (length - thumb) * offset / metrics.maximum();
                    root.fill(new Rectangle2D.Double(metrics.horizontal() ? b.x() : b.x() + b.width() - 6,
                            metrics.horizontal() ? b.y() + b.height() - 6 : b.y(), metrics.horizontal() ? b.width() : 6, metrics.horizontal() ? 6 : b.height()));
                    root.setColor(new Color(0xDDDDDD));
                    root.fill(new Rectangle2D.Double(metrics.horizontal() ? b.x() + position : b.x() + b.width() - 6,
                            metrics.horizontal() ? b.y() + b.height() - 6 : b.y() + position, metrics.horizontal() ? thumb : 6, metrics.horizontal() ? 6 : thumb));
                }
            }
        } finally { root.dispose();
            }
        return png(image);
    }
    private boolean pixelArt(BufferedImage source) {
        if (source.getWidth() > 128 || source.getHeight() > 128) return false;
        var palette = new java.util.HashSet<Integer>();
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            palette.add(source.getRGB(x, y));
            if (palette.size() > 64) return false;
        }
        return true;
    }
    private byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("asset.encoder");
        return bytes.toByteArray();
    }
}
