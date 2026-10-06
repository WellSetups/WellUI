package com.wellsetups.wellui.core;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.wellsetups.wellui.api.document.UiDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/** Stable human-facing aliases; documents, permission nodes and graph references retain their internal IDs. */
final class ProjectSlugs {
    private final Path file;
    private volatile Map<String, String> names = Map.of();
    ProjectSlugs(Path root) throws IOException {
        file = root.resolve("project-slugs.dat");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 262144) throw new IOException("storage.slugs");
            try {
                var parsed = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                var next = new TreeMap<String, String>();
                var unique = new HashSet<String>();
                if (parsed.size() > 1024) throw new IllegalArgumentException();
                for (var entry : parsed.entrySet()) {
                    String slug = entry.getValue().getAsString();
                    if (!DocumentValidator.validId(entry.getKey()) || !DocumentValidator.validId(slug) || !unique.add(slug)) throw new IllegalArgumentException();
                    next.put(entry.getKey(), slug);
                }
                names = Map.copyOf(next);
            } catch (RuntimeException invalid) { throw new IOException("storage.slugs", invalid); }
        }
    }
    Map<String, String> names() { return names; }
    synchronized void register(Collection<UiDocument> documents) throws IOException {
        var next = new TreeMap<>(names);
        var ids = new HashSet<String>();
        documents.forEach(d -> ids.add(d.id()));
        next.keySet().retainAll(ids);
        var reserved = new HashSet<>(next.values());
        reserved.addAll(ids);
        for (var doc : documents.stream().sorted(java.util.Comparator.comparing(UiDocument::id)).toList()) {
            if (next.containsKey(doc.id())) continue;
            if (next.containsValue(doc.id())) throw new IOException("storage.slug_conflict");
            String base = java.text.Normalizer.normalize(doc.name(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(java.util.Locale.ROOT).replace('ı', 'i').replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (base.isEmpty()) base = "interface";
            base = base.substring(0, Math.min(48, base.length())).replaceAll("-$", "");
            if (!DocumentValidator.validId(base)) base = "interface-" + base;
            String slug = base;
            int suffix = 2;
            while (reserved.contains(slug) && !slug.equals(doc.id())) slug = base + "-" + suffix++;
            reserved.add(slug);
            next.put(doc.id(), slug);
        }
        if (next.size() > 1024) throw new IOException("storage.project_limit");
        if (!next.equals(names)) {
            AtomicFiles.write(file, new Gson().toJson(next).getBytes(StandardCharsets.UTF_8));
            names = Map.copyOf(next);
        }
    }
}
