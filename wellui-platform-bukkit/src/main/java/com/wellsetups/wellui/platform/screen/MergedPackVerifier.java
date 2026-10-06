package com.wellsetups.wellui.platform.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import static com.wellsetups.wellui.platform.screen.PackProblem.Code;

/** Read-only semantic inspection of the current compiled contribution, not an output byte lock. */
final class MergedPackVerifier {
    private final com.wellsetups.wellui.core.screen.ScreenProfile profile;
    MergedPackVerifier() { this(com.wellsetups.wellui.core.screen.ScreenProfile.target()); }
    MergedPackVerifier(com.wellsetups.wellui.core.screen.ScreenProfile profile) { this.profile = profile; }
    private static final String ATLAS = "assets/minecraft/atlases/items.json";
    private ZipFile zip;
    private final Map<String, ZipEntry> physical = new HashMap<>();
    private final Map<String, String> effective = new HashMap<>();
    private JsonObject metadata;
    private JsonObject atlasSources;
    private long bytesRead;
    private String inspecting = "compiled ZIP/metadata";
    private final Map<String, String> spriteCache = new HashMap<>();
    private String models = "UNKNOWN";
    private String pixels = "UNKNOWN";
    private int rewrites;
    String models() { return models; }
    String pixels() { return pixels; }
    int rewrites() { return rewrites; }
    void verify(Path file, Map<String, byte[]> expected) throws IOException {
        try (var opened = new ZipFile(file.toFile())) {
            zip = opened;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (physical.size() >= 100000 || name.startsWith("/") || name.contains("\\") || name.contains("../")) throw problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, "ZIP directory");
                if (physical.putIfAbsent(name, entry) != null) throw problem(name.contains("assets/wellui/") ? Code.WELLUI_RESOURCE_SHADOWED : Code.ITEMSADDER_OUTPUT_UNREADABLE, name);
            }
            if (!physical.containsKey("pack.mcmeta")) throw problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, "pack.mcmeta");
            metadata = json(read("pack.mcmeta", 262144), "pack.mcmeta");
            if (!includesProfile(metadata.getAsJsonObject("pack"))) throw problem(Code.OUTPUT_PROFILE_UNSUPPORTED, "pack.mcmeta");
            var layers = new ArrayList<String>();
            layers.add("");
            if (metadata.has("overlays")) {
                for (var element : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
                    var overlay = element.getAsJsonObject();
                    if (!includesProfile(overlay)) continue;
                    String directory = overlay.get("directory").getAsString();
                    if (!directory.matches("[a-zA-Z0-9_.-]+") || directory.equals(".") || directory.equals("..")) throw problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, "pack.mcmeta overlays");
                    layers.add(directory + "/");
                }
            }
            for (String layer : layers) for (String name : physical.keySet()) {
                if (name.startsWith(layer + "assets/") && !physical.get(name).isDirectory()) effective.put(name.substring(layer.length()), name);
            }
            var sourceSemantics = new RenderingSemantics(name -> {
                byte[] value = expected.get(name);
                if (value == null) throw new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, name, "source parent missing");
                return value;
            }, key -> key);
            var compiledSemantics = new RenderingSemantics(name -> {
                String resolved = effective.get(name);
                if (resolved == null) throw new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, name, "MODEL_RESOLUTION parent missing or external");
                return read(resolved, 262144);
            }, this::sprite);
            for (var resource : new java.util.TreeMap<>(expected).entrySet()) {
                String name = resource.getKey();
                String resolved = effective.get(name);
                if (resolved == null) throw problem(Code.ITEMSADDER_BUILD_REQUIRED, name);
                byte[] compiled = read(resolved, name.endsWith(".png") ? 16 * 1024 * 1024 : 262144);
                if (name.endsWith(".png")) {
                    pixels = "FAILED";
                    if (!samePixels(resource.getValue(), compiled)) throw new PackProblem(Code.WELLUI_RESOURCE_SHADOWED, name, "TEXTURE pixels/dimensions changed");
                    String sidecar = effective.get(name + ".mcmeta");
                    if (sidecar != null) {
                        var extra = json(read(sidecar, 262144), sidecar);
                        if (extra.has("animation")) throw problem(Code.WELLUI_RESOURCE_SHADOWED, name + ".mcmeta");
                        if (extra.has("texture")) {
                            var texture = extra.getAsJsonObject("texture");
                            for (var field : texture.entrySet()) {
                                if (!Set.of("blur", "clamp").contains(field.getKey()) || field.getValue().getAsBoolean()) throw problem(Code.WELLUI_RESOURCE_SHADOWED, name + ".mcmeta");
                            }
                        }
                    }
                    pixels = "UNKNOWN";
                } else if (name.startsWith("assets/wellui/font/")) {
                    if (!json(resource.getValue(), name).equals(json(compiled, name))) throw problem(Code.WELLUI_RESOURCE_SHADOWED, name);
                    if (!java.util.Arrays.equals(resource.getValue(), compiled)) rewrites++;
                } else {
                    models = "FAILED";
                    boolean item = name.startsWith("assets/wellui/items/");
                    inspecting = name;
                    var wanted = item ? sourceSemantics.item(resource.getValue(), name) : sourceSemantics.model(resource.getValue(), name);
                    var actual = item ? compiledSemantics.item(compiled, name) : compiledSemantics.model(compiled, name);
                    RenderingSemantics.compare(item ? "ITEM_DEFINITION" : "MODEL_SEMANTICS", name, wanted, actual);
                    if (!java.util.Arrays.equals(resource.getValue(), compiled)) rewrites++;
                    models = "UNKNOWN";
                }
            }
            models = "OK";
            pixels = "OK";
            atlas(expected);
        } catch (PackProblem failure) { throw failure; }
        catch (IOException | RuntimeException malformed) {
            var failure = problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, inspecting);
            failure.initCause(malformed);
            throw failure;
        }
    }
    private boolean includesProfile(JsonObject section) {
        long target = profile.major() * 1000L + profile.minor();
        if (section.has("min_format") && section.has("max_format")) return bound(section.get("min_format"), false) <= target && bound(section.get("max_format"), true) >= target;
        JsonElement range = section.has("formats") ? section.get("formats") : section.get("supported_formats");
        if (range != null) {
            if (range.isJsonArray()) return range.getAsJsonArray().get(0).getAsInt() <= profile.major() && range.getAsJsonArray().get(1).getAsInt() >= profile.major();
            if (range.isJsonObject()) return range.getAsJsonObject().get("min_inclusive").getAsInt() <= profile.major() && range.getAsJsonObject().get("max_inclusive").getAsInt() >= profile.major();
            return range.getAsInt() == profile.major();
        }
        return section.has("pack_format") && section.get("pack_format").getAsInt() == profile.major();
    }
    private long bound(JsonElement value, boolean max) {
        if (!value.isJsonArray()) return value.getAsLong() * 1000 + (max ? 999 : 0);
        var array = value.getAsJsonArray();
        return array.get(0).getAsLong() * 1000 + (array.size() > 1 ? array.get(1).getAsInt() : max ? 999 : 0);
    }
    private JsonObject json(byte[] bytes, String name) throws IOException { return RenderingSemantics.json(bytes, name); }
    private byte[] read(String name, int limit) throws IOException {
        inspecting = name;
        var entry = physical.get(name);
        if (entry == null) throw problem(Code.ITEMSADDER_BUILD_REQUIRED, name);
        if (entry.getSize() > limit) throw problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, name);
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            bytesRead += bytes.length;
            if (bytes.length > limit || bytesRead > 512L * 1024 * 1024) throw problem(Code.ITEMSADDER_OUTPUT_UNREADABLE, name);
            return bytes;
        }
    }
    private boolean samePixels(byte[] a, byte[] b) throws IOException {
        if (java.util.Arrays.equals(a, b)) return true;
        // Inspect headers before decoding to prevent compiled PNGs from bypassing the source dimension budget.
        if (a.length < 24 || b.length < 24 || !java.util.Arrays.equals(java.util.Arrays.copyOf(a, 8), java.util.Arrays.copyOf(b, 8))) return false;
        var ah = java.nio.ByteBuffer.wrap(a);
        var bh = java.nio.ByteBuffer.wrap(b);
        if (ah.getInt(16) != bh.getInt(16) || ah.getInt(20) != bh.getInt(20)) return false;
        var left = ImageIO.read(new ByteArrayInputStream(a));
        java.awt.image.BufferedImage right;
        try { right = ImageIO.read(new ByteArrayInputStream(b)); }
        catch (IOException protectedChecksum) {
            // Real IA artifact changes PNG CRC/Adler checksums; require identical decoded scanlines and every other chunk's data.
            return PngImageData.identical(a, b);
        }
        if (left == null || right == null || left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) return false;
        for (int y = 0; y < left.getHeight(); y++) for (int x = 0; x < left.getWidth(); x++) {
            int p = left.getRGB(x, y);
            int q = right.getRGB(x, y);
            if (p != q && ((p >>> 24) != 0 || (q >>> 24) != 0)) return false;
        }
        return true;
    }
    private void atlas(Map<String, byte[]> expected) throws IOException {
        for (String name : expected.keySet()) if (name.startsWith("assets/wellui/textures/item/") && name.endsWith(".png")) {
            String wanted = textureKey(name);
            String resource = sprite(wanted);
            if (!wanted.equals(resource)) throw new PackProblem(Code.WELLUI_RESOURCE_SHADOWED, wanted,
                    "ATLAS effective texture expected=" + wanted + " compiled=" + resource);
        }
    }
    private String sprite(String wanted) throws IOException {
        if (spriteCache.containsKey(wanted)) return spriteCache.get(wanted);
        boolean vanilla = true;
        if (metadata.has("filter")) for (var rule : metadata.getAsJsonObject("filter").getAsJsonArray("block")) {
            if (matches(rule.getAsJsonObject(), "minecraft:atlases/items.json")) vanilla = false;
        }
        // Vanilla's item/ directory source; aliases must be proven by the effective shared atlas.
        String resolved = vanilla && wanted.substring(wanted.indexOf(':') + 1).startsWith("item/")
                && effective.containsKey(texturePath(wanted)) ? wanted : null;
        String atlas = effective.get(ATLAS);
        if (atlas != null && atlasSources == null) atlasSources = json(read(atlas, 1024 * 1024), atlas);
        if (atlas != null) for (var element : atlasSources.getAsJsonArray("sources")) {
            var source = element.getAsJsonObject();
            String type = source.get("type").getAsString().replaceFirst("^minecraft:", "");
            switch (type) {
                case "filter" -> { if (matches(source.getAsJsonObject("pattern"), wanted)) resolved = null; }
                case "single" -> {
                    String resource = key(source.get("resource").getAsString());
                    String sprite = source.has("sprite") ? key(source.get("sprite").getAsString()) : resource;
                    if (wanted.equals(sprite)) resolved = resource;
                }
                case "directory" -> {
                    String directory = source.get("source").getAsString();
                    String prefix = source.get("prefix").getAsString();
                    if (!directory.matches("[a-z0-9_./-]*") || directory.contains("..") || !prefix.matches("[a-z0-9_./-]*") || prefix.contains("..")) throw problem(Code.ATLAS_UNSUPPORTED, atlas);
                    int split = wanted.indexOf(':');
                    String path = wanted.substring(split + 1);
                    if (path.startsWith(prefix)) {
                        String resource = key(wanted.substring(0, split + 1) + directory + "/" + path.substring(prefix.length()));
                        if (effective.containsKey(texturePath(resource))) resolved = resource;
                    }
                }
                case "unstitch" -> {
                    for (var region : source.getAsJsonArray("regions")) {
                        if (wanted.equals(key(region.getAsJsonObject().get("sprite").getAsString()))) throw problem(Code.ATLAS_UNSUPPORTED, wanted);
                    }
                }
                case "paletted_permutations" -> {
                    String separator = source.has("separator") ? source.get("separator").getAsString() : "_";
                    for (var texture : source.getAsJsonArray("textures")) for (String suffix : source.getAsJsonObject("permutations").keySet()) {
                        if (wanted.equals(key(texture.getAsString()) + separator + suffix)) throw problem(Code.ATLAS_UNSUPPORTED, wanted);
                    }
                }
                default -> throw problem(Code.ATLAS_UNSUPPORTED, atlas + " type=" + type);
            }
        }
        if (resolved == null || !effective.containsKey(texturePath(resolved))) throw problem(Code.ATLAS_SPRITE_MISSING, wanted);
        spriteCache.put(wanted, resolved);
        return resolved;
    }
    private boolean matches(JsonObject pattern, String key) throws IOException {
        try {
            int split = key.indexOf(':');
            return (!pattern.has("namespace") || Pattern.matches(pattern.get("namespace").getAsString(), key.substring(0, split)))
                    && (!pattern.has("path") || Pattern.matches(pattern.get("path").getAsString(), key.substring(split + 1)));
        } catch (RuntimeException malformed) { throw problem(Code.ATLAS_UNSUPPORTED, ATLAS + " filter"); }
    }
    private String key(String raw) throws IOException { return RenderingSemantics.location(raw, "atlas resource key"); }
    private String textureKey(String path) { return "wellui:" + path.substring("assets/wellui/textures/".length(), path.length() - 4); }
    private String texturePath(String key) { return "assets/" + key.replace(":", "/textures/") + ".png"; }
    private PackProblem problem(Code code, String resource) { return new PackProblem(code, resource); }
}
