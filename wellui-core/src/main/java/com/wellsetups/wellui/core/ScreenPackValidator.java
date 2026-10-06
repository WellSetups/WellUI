package com.wellsetups.wellui.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wellsetups.wellui.core.screen.DynamicText;
import com.wellsetups.wellui.core.screen.ScreenProfile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Validates the emitted ZIP, not builder intent. Restricted to WellUI's exact 1.21.11 generated profile. */
public final class ScreenPackValidator {
    public static final String ATLAS_STRATEGY = "minecraft:items / vanilla directory item -> item/ (no override)";
    public record Graph(String itemModel, String model, String texture) { }
    public record Report(String profile, String atlasStrategy, Graph base, Graph cursor, Map<String, Graph> hover, int sprites) {
        public Report { hover = Map.copyOf(hover); }
    }
    public Report validate(Path path) throws IOException {
        return validateEntries(read(path));
    }
    Report validateEntries(Map<String, byte[]> entries) throws IOException {
        try {
            Set<String> used = new HashSet<>(Set.of("pack.mcmeta", "wellui-screen-manifest.json"));
            var metadata = json(entries, "pack.mcmeta").getAsJsonObject("pack");
            var manifest = json(entries, "wellui-screen-manifest.json");
            var profile = ScreenProfile.named(manifest.get("profile").getAsString()).orElseThrow();
            var format = JsonParser.parseString("[" + profile.major() + "," + profile.minor() + "]");
            if (!format.equals(metadata.get("min_format")) || !format.equals(metadata.get("max_format"))
                    || metadata.has("supported_formats") || metadata.has("pack_format")) throw invalid();
            if (!manifest.get("version").equals(JsonParser.parseString("2"))) throw invalid();
            Set<String> sprites = new HashSet<>();
            int width = manifest.get("rasterWidth").getAsInt();
            int height = manifest.get("rasterHeight").getAsInt();
            double logicalWidth = manifest.get("width").getAsDouble();
            double logicalHeight = manifest.get("height").getAsDouble();
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 4_000_000
                    || !Double.isFinite(logicalWidth) || !Double.isFinite(logicalHeight) || logicalWidth < 32 || logicalHeight < 32
                    || Math.abs(width - height * logicalWidth / logicalHeight) > 1 + logicalWidth / logicalHeight) throw invalid();
            Graph base = graph(profile, entries, used, sprites, manifest.get("base").getAsString(), width, height);
            Graph cursor = graph(profile, entries, used, sprites, manifest.get("cursor").getAsString(), 32, 32);
            Map<String, Graph> hover = new TreeMap<>();
            var variants = manifest.getAsJsonObject("hover");
            if (variants.size() > 16 || (long) width * height * (variants.size() + 1) > 24_000_000) throw invalid();
            for (var variant : variants.entrySet()) hover.put(variant.getKey(), graph(profile, entries, used, sprites, variant.getValue().getAsString(), width, height));
            if (manifest.has("layers")) {
                var layers = manifest.getAsJsonArray("layers");
                if (layers.size() > 33 || !variants.isEmpty()) throw invalid();
                long pixels = (long) width * height;
                boolean font = false;
                int independent = 0, staticRuns = 0;
                Set<String> components = new HashSet<>();
                for (var element : layers) {
                    var layer = element.getAsJsonObject();
                    if (!components.add(layer.get("component").getAsString())) throw invalid();
                    int w = layer.get("pixelWidth").getAsInt(), h = layer.get("pixelHeight").getAsInt();
                    if (w <= 0 || h <= 0 || w > width || h > height) throw invalid();
                    var models = layer.getAsJsonArray("models");
                    var over = layer.getAsJsonArray("hover");
                    if (models.size() != 9 || !(over.isEmpty() || over.size() == 9)) throw invalid();
                    boolean isStatic = layer.get("component").getAsString().startsWith("@static-");
                    if (isStatic) {
                        if (++staticRuns > 17 || !over.isEmpty() || layer.get("dynamicText").getAsBoolean()
                                || models.asList().stream().distinct().count() != 1) throw invalid();
                    } else if (++independent > 16) throw invalid();
                    pixels += (long) w * h * (isStatic ? 1 : models.size() + over.size());
                    if (pixels > 24_000_000) throw invalid();
                    for (var key : models) graph(profile, entries, used, sprites, key.getAsString(), w, h);
                    for (var key : over) graph(profile, entries, used, sprites, key.getAsString(), w, h);
                    if (layer.has("variants")) {
                        var prepared = layer.getAsJsonObject("variants");
                        if (prepared.size() > 544) throw invalid();
                        for (var variant : prepared.entrySet()) {
                            if (!variant.getKey().matches("(?:scroll:[0-9]{1,2}:[a-z0-9_-]{0,64}|option:[0-9]{1,2}:(?:normal|hover)|(?:NORMAL|HOVER|FOCUSED|ACTIVE|DISABLED|INVALID):(?:true|false))")) throw invalid();
                            var levels = variant.getValue().getAsJsonArray();
                            if (levels.size() != 9) throw invalid();
                            for (var key : levels) graph(profile, entries, used, sprites, key.getAsString(), w, h);
                        }
                    }
                    font |= layer.get("dynamicText").getAsBoolean();
                }
                if (font) {
                    // Validate the closed provider definition without depending on the current host's AWT font pixels.
                    if (!json(entries, DynamicText.FONT_PATH).equals(JsonParser.parseString(DynamicText.definition()))) throw invalid();
                    byte[] texture = entries.get(DynamicText.TEXTURE_PATH);
                    if (texture == null) throw invalid();
                    png(texture, 128, DynamicText.textureHeight());
                    used.add(DynamicText.FONT_PATH);
                    used.add(DynamicText.TEXTURE_PATH);
                }
            }
            // Forbid unknown resources, including atlas additions/filters that could redefine or duplicate sprites.
            if (!entries.keySet().equals(used)) throw invalid();
            long spritePixels = 0;
            for (String sprite : sprites) {
                var header = java.nio.ByteBuffer.wrap(entries.get(resource("textures", sprite, ".png")));
                spritePixels += (long) header.getInt(16) * header.getInt(20);
            }
            if (spritePixels > 24_000_000) throw invalid();
            return new Report(profile.minecraft() + " / resource " + profile.major() + "." + profile.minor(), ATLAS_STRATEGY, base, cursor, hover, sprites.size());
        } catch (RuntimeException malformed) {
            throw new IOException("screen.graph_invalid", malformed);
        }
    }
    static Map<String, byte[]> read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 64 * 1024 * 1024) throw invalid();
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                String name = entry.getName();
                if (entries.size() >= 4096 || !name.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")
                        || java.util.Arrays.stream(name.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))
                        || entries.containsKey(name) || entry.isDirectory()) throw invalid();
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                    total += bytes.length;
                    if (bytes.length > 16 * 1024 * 1024 || total > 616 * 1024 * 1024) throw invalid();
                    entries.put(name, bytes);
                }
            }
        }
        return entries;
    }
    private Graph graph(ScreenProfile profile, Map<String, byte[]> entries, Set<String> used, Set<String> sprites, String itemKey, int width, int height) throws IOException {
        if (!itemKey.matches("wellui:screen/[0-9a-f]{64}")) throw invalid();
        String itemPath = resource("items", itemKey, ".json");
        var definition = json(entries, itemPath).getAsJsonObject("model");
        if (!definition.get("type").getAsString().equals("minecraft:model")) throw invalid();
        String modelKey = definition.get("model").getAsString();
        if (!modelKey.equals(itemKey)) throw invalid();
        String modelPath = resource("models", modelKey, ".json");
        var model = json(entries, modelPath);
        var textures = model.getAsJsonObject("textures");
        if (model.has("parent") || textures.size() != 1 || !textures.has("screen")) throw invalid();
        String textureKey = textures.get("screen").getAsString();
        // Verified against Mojang 1.21.11 assets/minecraft/atlases/items.json:
        // directory source=item, prefix=item/, across namespaces. textures/screen is NOT included.
        if (!textureKey.equals("wellui:item/" + itemKey.substring("wellui:".length()))) throw invalid();
        String texturePath = resource("textures", textureKey, ".png");
        byte[] png = entries.get(texturePath);
        if (png == null || !textureKey.endsWith("/" + AssetStore.hash("SHA-256", png))) throw invalid();
        if (!sprites.contains(textureKey)) png(png, width, height);
        else {
            var header = java.nio.ByteBuffer.wrap(png);
            if (header.getInt(16) != width || header.getInt(20) != height) throw invalid();
        }
        var elements = model.getAsJsonArray("elements");
        if (elements.size() != 1) throw invalid();
        var element = elements.get(0).getAsJsonObject();
        if (profile.minecraft().equals("26.3")) {
            if (element.has("shade") || !element.has("shade_direction_override") || !element.get("shade_direction_override").getAsString().equals("up")) throw invalid();
        } else if (!element.has("shade") || element.get("shade").getAsBoolean() || element.has("shade_direction_override")) throw invalid();
        var faces = elements.get(0).getAsJsonObject().getAsJsonObject("faces");
        if (!faces.keySet().equals(Set.of("north"))) throw invalid();
        if (!faces.getAsJsonObject("north").get("uv").equals(JsonParser.parseString("[0,0,16,16]"))) throw invalid();
        for (var face : faces.entrySet()) {
            if (!face.getValue().getAsJsonObject().get("texture").getAsString().equals("#screen")) throw invalid();
        }
        used.add(itemPath);
        used.add(modelPath);
        used.add(texturePath);
        sprites.add(textureKey);
        // Multiple graph roots may intentionally share a single content-addressed sprite.
        return new Graph(itemKey, modelKey, textureKey);
    }
    private void png(byte[] bytes, int width, int height) throws IOException {
        if (bytes.length < 8 || bytes[0] != (byte) 137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71) throw invalid();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height || reader.read(0) == null) throw invalid();
            } finally { reader.dispose();
                }
        }
    }
    private JsonObject json(Map<String, byte[]> entries, String name) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null || bytes.length > 256 * 1024) throw invalid();
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private String resource(String kind, String key, String suffix) {
        return "assets/wellui/" + kind + "/" + key.substring("wellui:".length()) + suffix;
    }
    private static IOException invalid() { return new IOException("screen.graph_invalid"); }
}
