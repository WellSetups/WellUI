package com.wellsetups.wellui.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Versioned append-only content allocations. Deleted content retains its code point (tombstone). */
public final class GlyphRegistry {
    private static final String HEADER = "WELLUI_GLYPHS_1";
    private final Path file;

    public GlyphRegistry(Path directory) throws IOException {
        file = AtomicFiles.directory(directory).resolve("glyphs-v1.tsv");
    }

    public synchronized Map<String, Integer> allocate(Set<String> hashes) throws IOException {
        TreeMap<String, Integer> entries = new TreeMap<>();
        Set<Integer> assigned = new HashSet<>();
        int next = 0xE000;
        if (Files.isSymbolicLink(file)) throw new IOException("storage.symlink");
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024) throw new IOException("hud.registry_invalid");
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(HEADER)) throw new IOException("hud.registry_invalid");
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")) throw new IOException("hud.registry_invalid");
                int value;
                try { value = Integer.parseInt(fields[1]); }
                catch (NumberFormatException invalid) { throw new IOException("hud.registry_invalid", invalid); }
                if (value < 0xE000 || value > 0xF8FF || entries.put(fields[0], value) != null || !assigned.add(value)) {
                    throw new IOException("hud.registry_invalid");
                }
                next = Math.max(next, value + 1);
            }
        }
        boolean changed = !Files.exists(file);
        for (String hash : new TreeSet<>(hashes)) {
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("hud.registry_invalid");
            if (!entries.containsKey(hash)) {
                if (next > 0xF8FF) throw new IOException("hud.registry_full");
                entries.put(hash, next++);
                changed = true;
            }
        }
        if (changed) {
            StringBuilder contents = new StringBuilder(HEADER).append('\n');
            entries.forEach((hash, glyph) -> contents.append(hash).append('\t').append(glyph).append('\n'));
            AtomicFiles.write(file, contents.toString().getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Integer> result = new TreeMap<>();
        for (String hash : hashes) result.put(hash, entries.get(hash));
        return Map.copyOf(result);
    }
}
