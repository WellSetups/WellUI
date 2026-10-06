package com.wellsetups.wellui.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic export-only legacy pack foundation. Does not touch external packs or prompt players. */
public final class ResourcePackBuilder {
    public record PackResult(Path path, String sha1, String sha256) { }
    private final AssetStore assets;
    private final Path output;

    public ResourcePackBuilder(AssetStore assets, Path output) throws IOException {
        this.assets = assets;
        this.output = AtomicFiles.directory(output);
    }

    public synchronized PackResult build(UiDocument document, int packFormat) throws IOException {
        // New minor-version pack metadata requires a dedicated adapter; never pretend it is legacy.
        if (packFormat < 15 || packFormat > 64) throw new IllegalArgumentException("pack.format_unsupported");
        String json = new DocumentCodec().encode(document);
        TreeSet<String> hashes = new TreeSet<>();
        for (var placed : new LayoutEngine().layout(document, document.width(), document.height())) {
            if (placed.component() instanceof ImageComponent image) hashes.add(image.asset());
        }
        Map<String, byte[]> entries = new TreeMap<>();
        JsonArray providers = new JsonArray();
        JsonObject allocations = new JsonObject();
        int glyph = 0xE000;
        long totalBytes = 0;
        for (String hash : hashes) {
            byte[] image = assets.read(hash);
            totalBytes += image.length;
            if (totalBytes > 32 * 1024 * 1024) throw new IllegalArgumentException("pack.size_limit");
            entries.put("assets/wellui/textures/font/" + hash + ".png", image);
            String character = Character.toString(glyph++);
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", "wellui:font/" + hash + ".png");
            provider.addProperty("ascent", 8);
            provider.addProperty("height", 16);
            JsonArray chars = new JsonArray();
            chars.add(character);
            provider.add("chars", chars);
            providers.add(provider);
            allocations.addProperty(hash, character);
        }
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("welluiVersion", "1.0.0");
        manifest.addProperty("schemaVersion", document.schemaVersion());
        manifest.addProperty("project", document.id());
        manifest.addProperty("documentSha256", AssetStore.hash("SHA-256", utf8(json)));
        manifest.add("assets", allocations);
        Gson gson = new Gson();
        var metadata = new JsonObject();
        var pack = new JsonObject();
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("description", document.name());
        metadata.add("pack", pack);
        entries.put("pack.mcmeta", utf8(gson.toJson(metadata)));
        entries.put("assets/wellui/font/default.json", utf8(gson.toJson(font)));
        entries.put("wellui-manifest.json", utf8(gson.toJson(manifest)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        byte[] content = bytes.toByteArray();
        String sha256 = AssetStore.hash("SHA-256", content);
        Path destination = output.resolve(document.id() + ".zip");
        if (Files.isSymbolicLink(destination)) throw new IOException("storage.symlink");
        if (!Files.exists(destination) || Files.size(destination) != content.length
                || !AssetStore.hash("SHA-256", Files.readAllBytes(destination)).equals(sha256)) {
            AtomicFiles.write(destination, content);
        }
        return new PackResult(destination, AssetStore.hash("SHA-1", content), sha256);
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
