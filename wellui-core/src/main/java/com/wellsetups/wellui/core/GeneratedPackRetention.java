package com.wellsetups.wellui.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** IO-boundary collection of managed ZIPs; current exports and in-flight download grace survive. */
public final class GeneratedPackRetention {
    private GeneratedPackRetention() { }
    public static void prune(Path directory, Set<Path> protectedPaths) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage.symlink");
        record Entry(Path path, long modified, long size) { }
        var candidates = new java.util.ArrayList<Entry>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.zip")).limit(4097).toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage.symlink");
                candidates.add(new Entry(path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(), Files.size(path)));
            }
        }
        if (candidates.size() > 4096) throw new IOException("screen.export_limit");
        candidates.sort(java.util.Comparator.comparingLong(Entry::modified).reversed());
        long grace = System.currentTimeMillis() - 600_000, expiry = System.currentTimeMillis() - java.time.Duration.ofDays(7).toMillis(), bytes = 0;
        int count = 0;
        var protectedFiles = protectedPaths.stream().map(p -> p.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        for (var entry : candidates) {
            if (protectedFiles.contains(entry.path()) || entry.modified() >= grace) continue;
            if (entry.modified() < expiry || count >= 64 || bytes + entry.size() > 512L * 1024 * 1024) Files.delete(entry.path());
            else { count++;
                bytes += entry.size();
            }
        }
    }
}
