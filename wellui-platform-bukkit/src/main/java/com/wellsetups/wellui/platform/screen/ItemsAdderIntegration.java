package com.wellsetups.wellui.platform.screen;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.wellsetups.wellui.core.AssetStore;
import com.wellsetups.wellui.core.AtomicFiles;
import com.wellsetups.wellui.core.ScreenGenerationBuilder;
import com.wellsetups.wellui.core.ScreenGenerationBuilder.Generation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import static com.wellsetups.wellui.platform.screen.PackProblem.Code;

/** Owns source contributions only. ItemsAdder owns compilation, the shared atlas and delivery. */
public final class ItemsAdderIntegration implements ResourcePackIntegration {
    public enum Ownership { WELLUI_MANAGED_SOURCE, ITEMSADDER_MANAGED_SOURCE, ITEMSADDER_GENERATED_OUTPUT, SHARED_GENERATED_ATLAS, UNKNOWN_EXTERNAL }
    public static final String SOURCE_ROOT = "contents/wellui/resourcepack";
    private final Path root;
    private final Path managed;
    private final Path merged;
    private final Path marker;
    private record Manifest(String generation, Map<String, String> files, boolean legacy) { }
    private record Verified(String generation, String sha1) { }
    private volatile Verified verified = new Verified("", "");
    private volatile IntegrationReport report = IntegrationReport.unknown();
    public ItemsAdderIntegration(Path root, String mergedFile) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        managed = this.root.resolve(SOURCE_ROOT);
        merged = this.root.resolve(mergedFile).normalize();
        marker = managed.getParent().resolve(".wellui-managed.json");
        safe(managed);
        safe(merged);
    }
    public String owner() { return "ItemsAdder"; }
    public String sourceRoot() { return SOURCE_ROOT; }
    public String compiledPack() { return relativeOutput(); }
    public String compiledStamp() throws IOException {
        safe(merged);
        if (!Files.exists(merged, LinkOption.NOFOLLOW_LINKS)) return "MISSING";
        var attrs = Files.readAttributes(merged, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.size() + ":" + attrs.lastModifiedTime() + ":" + attrs.creationTime() + ":" + attrs.fileKey();
    }
    public IntegrationReport report() { return report; }
    public Ownership ownership(String path) {
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) return Ownership.UNKNOWN_EXTERNAL;
        if (path.equals("assets/minecraft/atlases/items.json")) return Ownership.SHARED_GENERATED_ATLAS;
        if (path.equals(root.relativize(merged).toString().replace('\\', '/'))) return Ownership.ITEMSADDER_GENERATED_OUTPUT;
        if (path.startsWith(SOURCE_ROOT + "/assets/wellui/")) return Ownership.WELLUI_MANAGED_SOURCE;
        if (path.startsWith("contents/") && !path.startsWith("contents/wellui/")) return Ownership.ITEMSADDER_MANAGED_SOURCE;
        return Ownership.UNKNOWN_EXTERNAL;
    }
    private void safe(Path path) throws IOException {
        if (!path.normalize().startsWith(root)) throw new PackProblem(Code.UNSAFE_PATH, "managed path");
        for (Path next = path; next != null; next = next.getParent()) {
            if (Files.isSymbolicLink(next) || Files.exists(next, LinkOption.NOFOLLOW_LINKS) && !next.toRealPath().equals(next.toAbsolutePath().normalize())) {
                throw new PackProblem(Code.UNSAFE_PATH, "managed path");
            }
        }
    }
    private static boolean ownedName(String name) {
        return name.matches("assets/wellui/(?:items/screen|models/screen)/[0-9a-f]{64}\\.json")
                || name.matches("assets/wellui/textures/item/screen/[0-9a-f]{64}\\.png")
                || name.equals(com.wellsetups.wellui.core.screen.DynamicText.FONT_PATH)
                || name.equals(com.wellsetups.wellui.core.screen.DynamicText.TEXTURE_PATH);
    }
    private Manifest manifest(boolean required) throws IOException {
        safe(marker);
        if (!Files.exists(marker)) {
            if (required) throw new PackProblem(Code.WELLUI_SOURCE_MISSING, "contents/wellui/.wellui-managed.json");
            return new Manifest("UNKNOWN", Map.of(), false);
        }
        if (!Files.isRegularFile(marker) || Files.size(marker) > 1024 * 1024) throw new PackProblem(Code.SOURCE_MANIFEST_INVALID, "contents/wellui/.wellui-managed.json");
        try {
            var json = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            boolean legacy = !json.has("version");
            if (!legacy && (json.get("version").getAsInt() != 2 || !json.keySet().equals(java.util.Set.of("version", "generation", "files")))) throw new IllegalArgumentException();
            String generation = legacy ? "UNKNOWN" : json.get("generation").getAsString();
            if (!legacy && !generation.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            var object = legacy ? json : json.getAsJsonObject("files");
            Map<String, String> files = new TreeMap<>();
            for (var entry : object.entrySet()) {
                if (!ownedName(entry.getKey()) || !entry.getValue().getAsString().matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
                files.put(entry.getKey(), entry.getValue().getAsString());
            }
            return new Manifest(generation, files, legacy);
        } catch (RuntimeException invalid) { throw new PackProblem(Code.SOURCE_MANIFEST_INVALID, "contents/wellui/.wellui-managed.json"); }
    }
    private Map<String, byte[]> resources(Generation generation) throws IOException {
        ScreenGenerationBuilder.validate(generation.path());
        Map<String, byte[]> result = new TreeMap<>();
        try (var zip = new ZipFile(generation.path().toFile())) {
            for (var entry : zip.stream().filter(e -> e.getName().startsWith("assets/wellui/")).toList()) {
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        }
        return result;
    }
    private Map<String, String> hashes(Map<String, byte[]> resources) {
        Map<String, String> result = new TreeMap<>();
        resources.forEach((name, bytes) -> result.put(name, AssetStore.hash("SHA-256", bytes)));
        return result;
    }
    private void checkFiles(Manifest manifest, boolean repair) throws IOException {
        safe(managed);
        for (var entry : manifest.files().entrySet()) {
            Path file = managed.resolve(entry.getKey());
            safe(file);
            if (!Files.exists(file)) {
                if (!repair) throw new PackProblem(Code.WELLUI_SOURCE_MISSING, entry.getKey());
            } else if (!Files.isRegularFile(file) || Files.size(file) > 16 * 1024 * 1024
                    || !AssetStore.hash("SHA-256", Files.readAllBytes(file)).equals(entry.getValue())) {
                if (!repair || !Files.isRegularFile(file)) throw new PackProblem(Code.WELLUI_SOURCE_MODIFIED, entry.getKey());
            }
        }
        Path namespace = managed.resolve("assets/wellui");
        safe(namespace);
        if (Files.exists(namespace)) try (var walk = Files.walk(namespace)) {
            for (Path file : walk.toList()) {
                safe(file);
                if (Files.isRegularFile(file) && !manifest.files().containsKey(managed.relativize(file).toString().replace('\\', '/'))) {
                    throw new PackProblem(Code.EXTERNAL_NAMESPACE_COLLISION, managed.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
    }
    public synchronized void export(Generation generation) throws IOException { writeSource(generation, false); }
    public synchronized void repair(Generation generation) throws IOException { writeSource(generation, true); }
    private void writeSource(Generation generation, boolean repair) throws IOException {
        try {
            var next = resources(generation);
            var previous = manifest(false);
            checkFiles(previous, repair);
            // Preflight every target before mutation. Foreign files and link/reparse targets are never adopted.
            for (String name : next.keySet()) {
                Path target = managed.resolve(name);
                safe(target);
                if (Files.exists(target) && !previous.files().containsKey(name)) throw new PackProblem(Code.EXTERNAL_NAMESPACE_COLLISION, name);
            }
            for (var entry : previous.files().entrySet()) if (!next.containsKey(entry.getKey())) {
                Path file = managed.resolve(entry.getKey());
                if (Files.exists(file) && (!Files.isRegularFile(file) || Files.size(file) > 16 * 1024 * 1024
                        || !AssetStore.hash("SHA-256", Files.readAllBytes(file)).equals(entry.getValue()))) {
                    throw new PackProblem(Code.WELLUI_SOURCE_MODIFIED, entry.getKey());
                }
            }
            if (previous.legacy()) {
                Path backup = marker.resolveSibling(".wellui-managed-v1.backup.json");
                safe(backup);
                if (!Files.exists(backup)) AtomicFiles.write(backup, Files.readAllBytes(marker));
            }
            for (var entry : next.entrySet()) {
                Path file = managed.resolve(entry.getKey());
                safe(file);
                Files.createDirectories(file.getParent());
                if (repair || !AssetStore.hash("SHA-256", entry.getValue()).equals(previous.files().get(entry.getKey())) || !Files.exists(file)) {
                    AtomicFiles.write(file, entry.getValue());
                }
            }
            // Delete only unchanged, previously recorded source files. Preserve modified obsolete files for operator review.
            for (var entry : previous.files().entrySet()) if (!next.containsKey(entry.getKey())) {
                Path file = managed.resolve(entry.getKey());
                safe(file);
                if (Files.isRegularFile(file) && Files.size(file) <= 16 * 1024 * 1024
                        && AssetStore.hash("SHA-256", Files.readAllBytes(file)).equals(entry.getValue())) Files.delete(file);
                else if (Files.exists(file)) throw new PackProblem(Code.WELLUI_SOURCE_MODIFIED, entry.getKey());
            }
            byte[] bytes = new Gson().toJson(Map.of("version", 2, "generation", generation.sha256(), "files", hashes(next))).getBytes(StandardCharsets.UTF_8);
            safe(marker);
            if (!Files.exists(marker) || !java.util.Arrays.equals(bytes, Files.readAllBytes(marker))) AtomicFiles.write(marker, bytes);
            if (repair || previous.legacy() || !generation.sha256().equals(verified.generation())) {
                verified = new Verified("", "");
                report = new IntegrationReport(generation.sha256(), generation.sha256(), "UNKNOWN", next.size(), "OK", "UNKNOWN", "UNKNOWN",
                        new PackProblem(Code.ITEMSADDER_BUILD_REQUIRED, root.relativize(merged).toString().replace('\\', '/')), false);
            }
        } catch (PackProblem failure) {
            failed(generation, failure);
            throw failure;
        }
    }
    public synchronized String verify(Generation generation) throws IOException {
        try {
            var expected = resources(generation);
            var source = manifest(true);
            report = new IntegrationReport(generation.sha256(), source.generation(), "UNKNOWN", expected.size(), "UNKNOWN", "UNKNOWN", "UNKNOWN", null, source.legacy());
            checkFiles(source, false);
            if (!source.files().equals(hashes(expected)) || !source.legacy() && !source.generation().equals(generation.sha256())) {
                throw new PackProblem(Code.SOURCE_GENERATION_STALE, "contents/wellui/.wellui-managed.json");
            }
            report = new IntegrationReport(generation.sha256(), generation.sha256(), "UNKNOWN", expected.size(), "OK", "UNKNOWN", "UNKNOWN", null, source.legacy());
            safe(merged);
            if (!Files.exists(merged)) throw new PackProblem(Code.GENERATED_OUTPUT_NOT_BUILT, relativeOutput());
            if (!Files.isRegularFile(merged) || Files.size(merged) > 512L * 1024 * 1024) throw new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, relativeOutput());
            long size = Files.size(merged);
            var modified = Files.getLastModifiedTime(merged);
            var verifier = new MergedPackVerifier(ScreenGenerationBuilder.profile(generation.path()));
            try { verifier.verify(merged, expected); }
            finally { report = report.compilation(verifier.models(), verifier.pixels(), verifier.rewrites()); }
            String sha1;
            try (var input = Files.newInputStream(merged)) {
                var digest = java.security.MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                sha1 = java.util.HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            if (Files.size(merged) != size || !Files.getLastModifiedTime(merged).equals(modified)) throw new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, relativeOutput());
            verified = new Verified(generation.sha256(), sha1); // Current delivery fingerprint, never an ownership/integrity lock.
            report = new IntegrationReport(generation.sha256(), generation.sha256(), generation.sha256(), expected.size(), "OK", "OK", "OK", null, source.legacy()).compilation(verifier.models(), verifier.pixels(), verifier.rewrites());
            return sha1;
        } catch (PackProblem failure) {
            failed(generation, failure);
            throw failure;
        }
        catch (IOException | RuntimeException invalid) {
            var failure = new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, relativeOutput());
            failed(generation, failure);
            throw failure;
        }
    }
    private String relativeOutput() { return root.relativize(merged).toString().replace('\\', '/'); }
    private void failed(Generation generation, PackProblem failure) {
        verified = new Verified("", "");
        var old = report;
        boolean sourceFailure = switch (failure.code()) {
            case WELLUI_SOURCE_MODIFIED, WELLUI_SOURCE_MISSING, SOURCE_MANIFEST_INVALID, SOURCE_GENERATION_STALE,
                    EXTERNAL_NAMESPACE_COLLISION, UNSAFE_PATH -> true;
            default -> false;
        };
        report = new IntegrationReport(generation.sha256(), old.sourceGeneration(), "UNKNOWN", old.files(), sourceFailure ? "FAILED" : old.source(),
                sourceFailure ? "UNKNOWN" : failure.buildRequired() ? "STALE" : "FAILED", "UNKNOWN", failure, old.legacy()).compilation(old.models(), old.pixels(), old.rewrites());
    }
    public String verifiedHash(String generation) {
        var current = verified;
        return current.generation().equals(generation) ? current.sha1() : "";
    }
}
