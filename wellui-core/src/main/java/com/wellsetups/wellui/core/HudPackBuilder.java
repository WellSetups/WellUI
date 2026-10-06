package com.wellsetups.wellui.core;

import com.google.gson.Gson;
import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Separate format-15 PoC export. One immutable prepared frame, no client or Bukkit dependency. */
public final class HudPackBuilder {
    public record Prepared(UiDocument document, Path path, String sha1, String sha256, String frameHash,
                           int glyph, int elements, long bytes, long buildMillis) {
        public boolean matches(UiDocument current, String acknowledgment) {
            return document.equals(current) && sha256.equals(acknowledgment);
        }
    }
    private final Path output;
    private final HudRasterizer rasterizer;
    private final GlyphRegistry registry;
    public HudPackBuilder(AssetStore assets, Path output) throws IOException {
        this.output = AtomicFiles.directory(output);
        rasterizer = new HudRasterizer(assets);
        registry = new GlyphRegistry(this.output);
    }
    public synchronized Prepared build(UiDocument document) throws IOException {
        long started = System.nanoTime();
        var placements = new LayoutEngine().layout(document, HudRasterizer.WIDTH, HudRasterizer.HEIGHT);
        if (placements.stream().filter(p -> p.component() instanceof ButtonComponent).count() > 16) {
            throw new IllegalArgumentException("hud.button_limit");
        }
        byte[] png = rasterizer.render(document);
        String hash = AssetStore.hash("SHA-256", png);
        int glyph = registry.allocate(Set.of(hash)).get(hash);
        String character = Character.toString(glyph);
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put("assets/wellui/textures/hud/" + hash + ".png", png);
        // A full-width opaque matte stabilizes advance regardless of transparent component margins.
        entries.put("assets/wellui/font/hud_poc.json", json(Map.of("providers", List.of(Map.of(
                "type", "bitmap", "file", "wellui:hud/" + hash + ".png", "height", HudRasterizer.HEIGHT,
                "ascent", HudRasterizer.HEIGHT, "chars", List.of(character))))));
        entries.put("pack.mcmeta", json(Map.of("pack", Map.of("pack_format", 15, "description", "WellUI HUD PoC: " + document.name()))));
        entries.put("wellui-hud-manifest.json", json(Map.of("version", 1, "renderer", "glyph_hud_poc", "project", document.id(),
                "documentSha256", AssetStore.hash("SHA-256", new DocumentCodec().encode(document).getBytes(StandardCharsets.UTF_8)),
                "frameSha256", hash, "glyph", glyph, "width", HudRasterizer.WIDTH, "height", HudRasterizer.HEIGHT)));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(outputBytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        byte[] content = outputBytes.toByteArray();
        String sha256 = AssetStore.hash("SHA-256", content);
        Path path = output.resolve(document.id() + ".zip");
        if (Files.isSymbolicLink(path)) throw new IOException("storage.symlink");
        if (!Files.exists(path) || Files.size(path) != content.length
                || !AssetStore.hash("SHA-256", Files.readAllBytes(path)).equals(sha256)) AtomicFiles.write(path, content);
        return new Prepared(document, path, AssetStore.hash("SHA-1", content), sha256, hash, glyph, placements.size(),
                content.length, (System.nanoTime() - started) / 1_000_000);
    }
    private static byte[] json(Map<String, ?> map) {
        return new Gson().toJson(sorted(map)).getBytes(StandardCharsets.UTF_8);
    }
    // Map.of iteration order can vary between JVMs; normalize every object, including provider objects.
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, child) -> ordered.put((String) key, sorted(child)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(HudPackBuilder::sorted).toList();
        return value;
    }
}
