package com.wellsetups.wellui.core;

import com.google.gson.Gson;
import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.screen.ScreenProfile;
import com.wellsetups.wellui.core.screen.ScreenScene;
import com.wellsetups.wellui.core.screen.RasterPolicy;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Exact 1.21.11 item-model pack; IO-only preparation, preserved static path and bounded dynamic component layers. */
public final class ScreenPackBuilder {
    public record Layer(String component, double x, double y, double width, double height, int pixelWidth, int pixelHeight,
                        List<String> models, List<String> hover, boolean dynamicText, Map<String, List<String>> variants) {
        public Layer(String component, double x, double y, double width, double height, int pixelWidth, int pixelHeight,
                     List<String> models, List<String> hover, boolean dynamicText) {
            this(component, x, y, width, height, pixelWidth, pixelHeight, models, hover, dynamicText, Map.of());
        }
        public Layer { models = List.copyOf(models);
            hover = List.copyOf(hover);
            var copy = new TreeMap<String, List<String>>();
            variants.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            variants = java.util.Collections.unmodifiableMap(copy);
            }
    }
    public record Prepared(UiDocument document, ScreenScene scene, Path path, String sha1, String sha256,
                           String baseModel, String cursorModel, Map<String, String> hoverModels, long bytes, long millis,
                           ScreenPackValidator.Report validation, RasterPolicy.Size raster, List<Layer> layers) {
        public Prepared { hoverModels = Map.copyOf(hoverModels);
            layers = List.copyOf(layers);
            }
        public Prepared(UiDocument document, ScreenScene scene, Path path, String sha1, String sha256,
                        String baseModel, String cursorModel, Map<String, String> hoverModels, long bytes, long millis,
                        ScreenPackValidator.Report validation, RasterPolicy.Size raster) {
            this(document, scene, path, sha1, sha256, baseModel, cursorModel, hoverModels, bytes, millis, validation, raster, List.of());
        }
    }
    private final ScreenRasterizer rasterizer;
    private final RasterPolicy policy;
    private final Path output;
    private final ScreenProfile profile;
    public ScreenPackBuilder(AssetStore assets, Path output) throws IOException {
        this(assets, output, RasterPolicy.defaults());
    }
    public ScreenPackBuilder(AssetStore assets, Path output, RasterPolicy policy) throws IOException {
        this(assets, output, policy, ScreenProfile.target());
    }
    public ScreenPackBuilder(AssetStore assets, Path output, RasterPolicy policy, ScreenProfile profile) throws IOException {
        if (!ScreenProfile.named(profile.minecraft()).filter(profile::equals).isPresent()) throw new IllegalArgumentException("screen.version");
        this.profile = profile;
        this.policy = policy;
        this.rasterizer = new ScreenRasterizer(assets, policy);
        this.output = AtomicFiles.directory(output);
    }
    public synchronized Prepared build(UiDocument document) throws IOException {
        long start = System.nanoTime();
        ScreenScene scene = new ScreenScene(document);
        if (scene.buttons().size() > 16 || document.width() < 32 || document.height() < 32
                || document.width() / document.height() > 10 || document.height() / document.width() > 10) {
            throw new IllegalArgumentException("screen.document_limits");
        }
        boolean animated = !document.animations().isEmpty();
        boolean dynamic = animated || com.wellsetups.wellui.core.logic.LogicVisuals.dynamic(document);
        boolean layered = animated || !document.controls().isEmpty();
        var independent = new java.util.HashSet<>(com.wellsetups.wellui.core.animation.AnimationVisuals.independent(document));
        scene.viewports().values().forEach(v -> independent.removeAll(v.children()));
        if (layered && independent.size() > 16) throw new IllegalArgumentException("animation.screen_layers");
        if (dynamic && !layered && scene.placements().size() > 16) throw new IllegalArgumentException("logic.screen_layers");
        var text = com.wellsetups.wellui.core.logic.LogicVisuals.text(document);
        var size = policy.size(document.width(), document.height(), scene.buttons().size() + 1);
        if (dynamic) {
            int runs = 0;
            boolean inRun = false;
            for (var p : scene.placements()) {
                if (scene.clipOwner(p.component().id()) != null) continue;
                boolean next = layered && !independent.contains(p.component().id());
                if (next && !inRun) runs++;
                inRun = next;
            }
            double area = document.width() * document.height() * (1 + runs);
            for (var p : scene.placements()) if (scene.clipOwner(p.component().id()) == null && (!layered || independent.contains(p.component().id()))) {
                var c = document.controls().get(p.component().id());
                int variants = p.component() instanceof ButtonComponent ? 18 : 9;
                if (c != null && c.field()) variants = 54 * (c.valueType() == com.wellsetups.wellui.api.logic.LogicType.BOOLEAN ? 2 : 1);
                if (c != null && c.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SELECT) variants += c.options().size() * 18;
                var viewport = scene.viewports().get(p.component().id());
                if (viewport != null) variants = viewport.steps() * (1 + (int) scene.buttons().stream().filter(b -> viewport.children().contains(b.id())).count()) * 9;
                area += Math.max(0, Math.min(document.width(), p.x() + p.width()) - Math.max(0, p.x()))
                        * Math.max(0, Math.min(document.height(), p.y() + p.height()) - Math.max(0, p.y())) * variants;
            }
            double scale = Math.min(size.scale(), Math.sqrt(22_000_000 / area));
            if (!document.controls().isEmpty() && scale < .5) throw new IllegalArgumentException("control.raster_budget");
            size = new RasterPolicy.Size(Math.max(1, (int) (document.width() * scale)), Math.max(1, (int) (document.height() * scale)), scale);
        }
        Map<String, byte[]> entries = new TreeMap<>();
        UiDocument background = dynamic ? new UiDocument(1, document.id(), document.name(), document.width(), document.height(), List.of()) : document;
        String base = model(entries, rasterizer.raster(background, dynamic ? new ScreenScene(background) : scene, null, size));
        Map<String, String> hover = new TreeMap<>();
        var layers = new java.util.ArrayList<Layer>();
        if (dynamic) {
            var staticRun = new java.util.LinkedHashSet<String>();
            for (var p : scene.placements()) {
                if (scene.clipOwner(p.component().id()) != null) continue;
                if (layered && !independent.contains(p.component().id())) { staticRun.add(p.component().id());
                    continue;
                }
                if (!staticRun.isEmpty()) { addStatic(document, scene, size, entries, layers, staticRun);
                    staticRun.clear();
                }
                int x = Math.max(0, (int) Math.floor(p.x() * size.scale()));
                int y = Math.max(0, (int) Math.floor(p.y() * size.scale()));
                int w = Math.min(size.width(), (int) Math.ceil((p.x() + p.width()) * size.scale())) - x;
                int h = Math.min(size.height(), (int) Math.ceil((p.y() + p.height()) * size.scale())) - y;
                if (w <= 0 || h <= 0) continue;
                if (special(document, scene, p, size, entries, layers, x, y, w, h)) continue;
                var normal = new java.util.ArrayList<String>();
                var highlighted = new java.util.ArrayList<String>();
                for (int level = 0; level <= 8; level++) {
                    normal.add(model(entries, rasterizer.layer(document, scene, p.component().id(), text.contains(p.component().id()), null, size, level / 8.0, x, y, w, h)));
                    if (p.component() instanceof ButtonComponent) highlighted.add(model(entries, rasterizer.layer(document, scene, p.component().id(), text.contains(p.component().id()), p.component().id(), size, level / 8.0, x, y, w, h)));
                }
                layers.add(new Layer(p.component().id(), x / size.scale(), y / size.scale(), w / size.scale(), h / size.scale(), w, h, normal, highlighted, text.contains(p.component().id())));
            }
            if (!staticRun.isEmpty()) addStatic(document, scene, size, entries, layers, staticRun);
            if (!text.isEmpty()) entries.putAll(com.wellsetups.wellui.core.screen.DynamicText.resources());
        } else {
            for (ButtonComponent button : scene.buttons()) {
                if (scene.opacity(button.id()) > 0) hover.put(button.id(), model(entries, rasterizer.raster(document, scene, button.id(), size)));
            }
        }
        String cursor = model(entries, cursor());
        entries.put("pack.mcmeta", json(profile.metadata()));
        var manifest = new TreeMap<String, Object>();
        manifest.putAll(Map.of("version", 2, "profile", profile.minecraft(), "project", document.id(), "base", base, "cursor", cursor,
                "hover", hover, "width", document.width(), "height", document.height(), "rasterWidth", size.width(), "rasterHeight", size.height()));
        if (dynamic) manifest.put("layers", layers);
        if (layers.size() > 33 || layers.stream().filter(l -> !l.component().startsWith("@static-")).count() > 16) throw new IllegalArgumentException("control.screen_layers");
        entries.put("wellui-screen-manifest.json", json(manifest));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        byte[] content = bytes.toByteArray();
        if (content.length > 32 * 1024 * 1024) throw new IOException("pack.size_limit");
        String hash = AssetStore.hash("SHA-256", content);
        Path path = output.resolve(hash + ".zip");
        if (Files.isSymbolicLink(path)) throw new IOException("storage.symlink");
        if (!Files.exists(path) || !AssetStore.hash("SHA-256", Files.readAllBytes(path)).equals(hash)) AtomicFiles.write(path, content);
        var validation = new ScreenPackValidator().validate(path);
        return new Prepared(document, scene, path, AssetStore.hash("SHA-1", content), hash, base, cursor, hover,
                content.length, (System.nanoTime() - start) / 1_000_000, validation, size, layers);
    }
    private boolean special(UiDocument document, ScreenScene scene, LayoutEngine.PlacedComponent p, RasterPolicy.Size size,
                            Map<String, byte[]> entries, List<Layer> layers, int x, int y, int w, int h) throws IOException {
        String id = p.component().id();
        var control = document.controls().get(id);
        var viewport = scene.viewports().get(id);
        if (viewport == null && (control == null || !control.field())) return false;
        var variants = new TreeMap<String, List<String>>();
        if (viewport != null) {
            var hovers = new java.util.ArrayList<String>();
            hovers.add("");
            scene.buttons().stream().filter(b -> viewport.children().contains(b.id())).forEach(b -> hovers.add(b.id()));
            for (int step = 0; step < viewport.steps(); step++) for (String hover : hovers) {
                var models = new java.util.ArrayList<String>();
                for (int level = 0; level <= 8; level++) models.add(model(entries,
                        rasterizer.viewport(document, id, viewport.at(step), hover, size, level / 8.0, x, y, w, h)));
                variants.put("scroll:" + step + ":" + hover, models);
            }
        } else {
            int values = control.valueType() == com.wellsetups.wellui.api.logic.LogicType.BOOLEAN ? 2 : 1;
            for (var state : com.wellsetups.wellui.api.document.UiControl.State.values()) for (int value = 0; value < values; value++) {
                var models = new java.util.ArrayList<String>();
                for (int level = 0; level <= 8; level++) models.add(model(entries,
                        rasterizer.control(document, scene, id, size, level / 8.0, x, y, w, h, state, value == 1)));
                variants.put(state.name() + ":" + (value == 1), models);
            }
        }
        var normal = variants.get(viewport == null ? "NORMAL:false" : "scroll:0:");
        layers.add(new Layer(id, x / size.scale(), y / size.scale(), w / size.scale(), h / size.scale(), w, h,
                normal, List.of(), viewport == null, variants));
        if (control != null && control.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SLIDER) {
            double knob = com.wellsetups.wellui.core.control.ControlPaint.knobSize(p);
            int pixels = Math.max(1, (int) Math.ceil(knob * size.scale()));
            var models = new java.util.ArrayList<String>();
            for (int level = 0; level <= 8; level++) {
                var image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
                var g = image.createGraphics();
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, level / 8f));
                    g.setColor(Color.decode(p.component().style().color()));
                    g.fillOval(0, 0, pixels, pixels);
                } finally { g.dispose(); }
                models.add(model(entries, png(image)));
            }
            layers.add(new Layer("@knob-" + id, p.x(), p.y() + p.height() * .8 - knob / 2, knob, knob, pixels, pixels, models, List.of(), false));
        }
        if (control != null && control.kind() == com.wellsetups.wellui.api.document.UiControl.Kind.SELECT) popup(document, p, control, size, entries, layers);
        if (entries.size() > 4000) throw new IllegalArgumentException("control.pack_budget");
        return true;
    }
    private void popup(UiDocument document, LayoutEngine.PlacedComponent p, com.wellsetups.wellui.api.document.UiControl control,
                       RasterPolicy.Size size, Map<String, byte[]> entries, List<Layer> layers) throws IOException {
        double rowHeight = Math.max(20, Math.min(60, p.height()));
        int w = Math.max(1, (int) Math.ceil(p.width() * size.scale())), h = Math.max(1, (int) Math.ceil(rowHeight * size.scale()));
        var variants = new TreeMap<String, List<String>>();
        for (int index = 0; index < control.options().size(); index++) {
            var option = control.options().get(index);
            var style = p.component().style();
            var row = new ButtonComponent("option", new com.wellsetups.wellui.api.document.Layout(0, 0, p.width(), rowHeight, "top_left"),
                    new com.wellsetups.wellui.api.document.Style(style.background(), option.disabled() ? "#777777" : style.color(), 1, style.fontSize()),
                    option.label(), new com.wellsetups.wellui.api.document.UiAction("send_message", ""));
            var doc = new UiDocument(1, document.id(), document.name(), document.width(), document.height(), List.of(row));
            var scene = new ScreenScene(doc);
            for (String state : List.of("normal", "hover")) {
                var models = new java.util.ArrayList<String>();
                for (int level = 0; level <= 8; level++) models.add(model(entries, rasterizer.layer(doc, scene, "option", false,
                        state.equals("hover") && !option.disabled() ? "option" : null, size, level / 8.0, 0, 0, w, h)));
                variants.put("option:" + index + ":" + state, models);
            }
        }
        layers.add(new Layer("@popup-" + p.component().id(), 0, 0, p.width(), rowHeight, w, h,
                variants.get("option:0:normal"), List.of(), false, variants));
    }
    private void addStatic(UiDocument document, ScreenScene scene, RasterPolicy.Size size, Map<String, byte[]> entries,
                           java.util.List<Layer> layers, java.util.Set<String> ids) throws IOException {
        String key = model(entries, rasterizer.group(document, scene, size, ids));
        layers.add(new Layer("@static-" + layers.size(), 0, 0, document.width(), document.height(), size.width(), size.height(),
                java.util.Collections.nCopies(9, key), List.of(), false));
    }
    private String model(Map<String, byte[]> entries, byte[] png) {
        String hash = AssetStore.hash("SHA-256", png);
        String key = "wellui:screen/" + hash;
        String texture = "wellui:item/screen/" + hash;
        entries.put("assets/wellui/textures/item/screen/" + hash + ".png", png);
        entries.put("assets/wellui/items/screen/" + hash + ".json", json(Map.of("model", Map.of("type", "minecraft:model", "model", key))));
        // Only the north face exists. Its U axis maps to logical right (-local X).
        var faces = Map.of("north", Map.of("uv", List.of(0, 0, 16, 16), "texture", "#screen"));
        var element = new TreeMap<String, Object>(profile.shading());
        element.putAll(Map.of("from", List.of(0, 0, 8), "to", List.of(16, 16, 8), "faces", faces));
        entries.put("assets/wellui/models/screen/" + hash + ".json", json(Map.of("ambientocclusion", false, "gui_light", "front",
                "textures", Map.of("screen", texture), "elements", List.of(element))));
        return key;
    }
    private byte[] cursor() throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillOval(3, 3, 26, 26);
            g.setColor(new Color(0xffdc69));
            g.fillOval(6, 6, 20, 20);
            g.setColor(Color.WHITE);
            g.fillOval(13, 13, 6, 6);
        } finally { g.dispose();
            }
        return png(image);
    }
    private byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("asset.encoder");
        return bytes.toByteArray();
    }
    private static byte[] json(Object value) { return new Gson().toJson(sorted(value)).getBytes(StandardCharsets.UTF_8); }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, child) -> result.put((String) key, sorted(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ScreenPackBuilder::sorted).toList();
        return value;
    }
}
