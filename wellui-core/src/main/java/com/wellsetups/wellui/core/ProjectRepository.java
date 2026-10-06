package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.UiDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded documents stored atomically; one previous revision retained per project. */
public final class ProjectRepository {
    private final Path root;
    private final DocumentCodec codec;
    private final ProjectSlugs slugs;

    public ProjectRepository(Path root, DocumentCodec codec) throws IOException {
        this.root = AtomicFiles.directory(root);
        this.codec = codec;
        slugs = new ProjectSlugs(this.root);
        slugs.register(list());
    }

    public synchronized List<UiDocument> list() throws IOException {
        List<UiDocument> result = new ArrayList<>();
        try (var entries = Files.list(root)) {
            var paths = entries.filter(p -> p.getFileName().toString().endsWith(".json")).limit(1025).toList();
            if (paths.size() > 1024) throw new IOException("storage.project_limit");
            for (Path path : paths.stream().sorted().toList()) {
                String name = path.getFileName().toString();
                load(name.substring(0, name.length() - 5)).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    public synchronized Optional<UiDocument> load(String id) throws IOException {
        Path file = path(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            UiDocument document = codec.decode(new String(read(file), StandardCharsets.UTF_8));
            if (!id.equals(document.id()) || !new DocumentValidator().validate(document).isEmpty()) {
                throw new IOException("storage.invalid_document");
            }
            return Optional.of(document);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("storage.invalid_document", invalid);
        }
    }

    public synchronized void save(UiDocument document) throws IOException {
        Path file = path(document.id());
        byte[] next = codec.encode(document).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] previous = read(file);
            if (java.util.Arrays.equals(previous, next)) return;
            AtomicFiles.write(root.resolve(document.id() + ".json.bak"), previous);
        } else {
            try (var entries = Files.list(root)) {
                if (entries.filter(p -> p.getFileName().toString().endsWith(".json")).limit(1024).count() >= 1024) {
                    throw new IOException("storage.project_limit");
                }
            }
        }
        if (!slugs.names().containsKey(document.id())) {
            var documents = new ArrayList<>(list());
            documents.removeIf(d -> d.id().equals(document.id()));
            documents.add(document);
            slugs.register(documents);
        }
        AtomicFiles.write(file, next);
        pruneBackups();
    }
    /** Immutable snapshot: command lookup does not perform filesystem IO. */
    public java.util.Map<String, String> slugs() { return slugs.names(); }
    public String slug(String id) { return slugs.names().getOrDefault(id, id); }

    private Path path(String id) {
        if (!DocumentValidator.validId(id)) throw new IllegalArgumentException("validation.project_id");
        return root.resolve(id + ".json");
    }
    public synchronized long modified(String id) throws IOException { return Files.getLastModifiedTime(path(id), LinkOption.NOFOLLOW_LINKS).toMillis(); }
    public synchronized void delete(String id) throws IOException {
        Path file = path(id);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            AtomicFiles.write(root.resolve(id + ".json.bak"), read(file));
            Files.delete(file);
            slugs.register(list());
            pruneBackups();
        }
    }
    /** Retained backups also protect image resources against cleanup. */
    public synchronized List<UiDocument> retained() throws IOException {
        pruneBackups();
        var result = new ArrayList<>(list());
        try (var entries = Files.list(root)) {
            var backups = entries.filter(p -> p.getFileName().toString().endsWith(".json.bak")).limit(2049).toList();
            if (backups.size() > 2048) throw new IOException("storage.project_limit");
            for (Path file : backups) {
                result.add(codec.decode(new String(read(file), StandardCharsets.UTF_8)));
            }
        }
        return List.copyOf(result);
    }
    /** Previous/deleted revisions expire after 14 days, at most 128 files and 64 MiB per repository. */
    public synchronized void pruneBackups() throws IOException {
        record Backup(Path file, long modified, long bytes) { }
        var backups = new ArrayList<Backup>();
        try (var entries = Files.list(root)) {
            for (Path file : entries.filter(p -> p.getFileName().toString().matches("[a-z0-9][a-z0-9_-]{0,63}\\.json\\.bak")).limit(2049).toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage.symlink");
                backups.add(new Backup(file, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(), Files.size(file)));
            }
        }
        if (backups.size() > 2048) throw new IOException("storage.project_limit");
        backups.sort(java.util.Comparator.comparingLong(Backup::modified).reversed().thenComparing(b -> b.file().toString()));
        long bytes = 0, expiry = System.currentTimeMillis() - java.time.Duration.ofDays(14).toMillis();
        int count = 0;
        for (var backup : backups) {
            if (backup.modified() < expiry || count >= 128 || bytes + backup.bytes() > 64L * 1024 * 1024) Files.delete(backup.file());
            else { count++;
                bytes += backup.bytes();
            }
        }
    }

    private byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage.symlink");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(DocumentCodec.MAX_JSON_BYTES + 1);
            if (bytes.length > DocumentCodec.MAX_JSON_BYTES) throw new IOException("validation.document_size");
            return bytes;
        }
    }
}
