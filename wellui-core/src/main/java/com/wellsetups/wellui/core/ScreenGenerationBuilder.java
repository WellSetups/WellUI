package com.wellsetups.wellui.core;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Visual deployment generations. UI opens never invoke this IO boundary. */
public final class ScreenGenerationBuilder {
    public record Generation(Path path, String sha256, String sha1, long bytes, Map<String, String> projects) {
        public Generation { projects = Map.copyOf(projects); }
        public String id() { return sha256.substring(0, 16); }
    }
    private final Path output;
    public ScreenGenerationBuilder(Path output) throws IOException { this.output = AtomicFiles.directory(output); }
    public Generation build(Collection<ScreenPackBuilder.Prepared> packs) throws IOException {
        if (packs.isEmpty() || packs.size() > 64) throw new IOException("screen.generation_limit");
        Map<String, byte[]> entries = new TreeMap<>();
        Map<String, String> projects = new TreeMap<>();
        long expanded = 0;
        long pixels = 0;
        for (var pack : packs) {
            new ScreenPackValidator().validate(pack.path());
            projects.put(pack.document().id(), pack.sha256());
            try (var zip = new ZipFile(pack.path().toFile())) {
                for (var entry : zip.stream().toList()) {
                    String name = entry.getName().equals("wellui-screen-manifest.json")
                            ? "wellui-projects/" + pack.document().id() + ".json" : entry.getName();
                    byte[] bytes;
                    try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                    byte[] prior = entries.get(name);
                    if (prior != null && !java.util.Arrays.equals(prior, bytes)) throw new IOException("screen.graph_invalid");
                    if (prior == null) {
                        expanded += bytes.length;
                        if (name.endsWith(".png")) {
                            var header = java.nio.ByteBuffer.wrap(bytes);
                            pixels += (long) header.getInt(16) * header.getInt(20);
                        }
                        if (expanded > 128 * 1024 * 1024 || pixels > 32_000_000) throw new IOException("screen.generation_limit");
                        entries.put(name, bytes);
                    }
                }
            }
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var item : entries.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        byte[] content = bytes.toByteArray();
        if (content.length > 64 * 1024 * 1024) throw new IOException("screen.generation_limit");
        String sha256 = AssetStore.hash("SHA-256", content);
        Path path = output.resolve(sha256 + ".zip");
        if (Files.isSymbolicLink(path)) throw new IOException("storage.symlink");
        if (!Files.exists(path) || !AssetStore.hash("SHA-256", Files.readAllBytes(path)).equals(sha256)) AtomicFiles.write(path, content);
        validate(path);
        return new Generation(path, sha256, AssetStore.hash("SHA-1", content), content.length, projects);
    }
    public static void validate(Path path) throws IOException {
        Map<String, byte[]> entries = ScreenPackValidator.read(path);
        var used = new java.util.HashSet<String>();
        int count = 0;
        long pixels = 0;
        for (String name : entries.keySet()) {
            if (name.endsWith(".png")) {
                byte[] data = entries.get(name);
                if (data.length < 24) throw new IOException("screen.graph_invalid");
                var header = java.nio.ByteBuffer.wrap(data);
                pixels += (long) header.getInt(16) * header.getInt(20);
            }
            if (!name.matches("wellui-projects/[a-z0-9][a-z0-9_-]{0,63}\\.json")) continue;
            count++;
            Map<String, byte[]> project = new TreeMap<>();
            project.put("pack.mcmeta", entries.get("pack.mcmeta"));
            project.put("wellui-screen-manifest.json", entries.get(name));
            var manifest = JsonParser.parseString(new String(entries.get(name), StandardCharsets.UTF_8)).getAsJsonObject();
            var keys = new java.util.HashSet<String>();
            keys.add(manifest.get("base").getAsString());
            keys.add(manifest.get("cursor").getAsString());
            manifest.getAsJsonObject("hover").entrySet().forEach(e -> keys.add(e.getValue().getAsString()));
            if (manifest.has("layers")) {
                boolean font = false;
                for (var element : manifest.getAsJsonArray("layers")) {
                    var layer = element.getAsJsonObject();
                    layer.getAsJsonArray("models").forEach(key -> keys.add(key.getAsString()));
                    layer.getAsJsonArray("hover").forEach(key -> keys.add(key.getAsString()));
                    if (layer.has("variants")) layer.getAsJsonObject("variants").entrySet().forEach(variant -> variant.getValue().getAsJsonArray().forEach(key -> keys.add(key.getAsString())));
                    font |= layer.get("dynamicText").getAsBoolean();
                }
                if (font) for (String resource : java.util.List.of(com.wellsetups.wellui.core.screen.DynamicText.FONT_PATH,
                        com.wellsetups.wellui.core.screen.DynamicText.TEXTURE_PATH)) {
                    project.put(resource, entries.get(resource));
                    used.add(resource);
                }
            }
            for (String key : keys) {
                if (!key.matches("wellui:screen/[0-9a-f]{64}")) throw new IOException("screen.graph_invalid");
                for (String resource : java.util.List.of("items/" + key.substring(7) + ".json", "models/" + key.substring(7) + ".json",
                        "textures/item/" + key.substring(7) + ".png")) {
                    String entry = "assets/wellui/" + resource;
                    project.put(entry, entries.get(entry));
                    used.add(entry);
                }
            }
            new ScreenPackValidator().validateEntries(project);
            used.add(name);
        }
        used.add("pack.mcmeta");
        if (count < 1 || count > 64 || pixels > 32_000_000 || pixels < 1 || !used.equals(entries.keySet())) throw new IOException("screen.generation_limit");
    }
    /** IO-only profile identity for external semantic validation. Caller validates the generation first. */
    public static com.wellsetups.wellui.core.screen.ScreenProfile profile(Path path) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            if (entry == null || entry.getSize() > 4096) throw new IOException("screen.graph_invalid");
            try (var input = zip.getInputStream(entry)) {
                var metadata = JsonParser.parseString(new String(input.readNBytes(4097), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
                var format = metadata.getAsJsonArray("min_format");
                return java.util.stream.Stream.of(com.wellsetups.wellui.core.screen.ScreenProfile.target(), com.wellsetups.wellui.core.screen.ScreenProfile.modern())
                        .filter(p -> p.major() == format.get(0).getAsInt() && p.minor() == format.get(1).getAsInt()).findFirst().orElseThrow();
            }
        } catch (RuntimeException invalid) { throw new IOException("screen.graph_invalid", invalid); }
    }
}
