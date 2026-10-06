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

/** Public ZIP-import boundary. Oraxen and Nexo have distinct documented import directories.
 * Provider compilation/delivery are external; only our hash-locked source ZIP is writable. */
public final class ImportedPackIntegration implements ResourcePackIntegration {
    public enum Provider {
        ORAXEN("Oraxen", "pack/uploads/wellui.zip"), NEXO("Nexo", "pack/external_packs/wellui.zip");
        private final String owner, source;
        Provider(String owner, String source) { this.owner = owner;
            this.source = source; }
    }
    private record Source(String generation, String sha256) { }
    private record Verified(String generation, String sha1) { }
    private final Provider provider;
    private final Path root, source, marker, compiled;
    private volatile Verified verified = new Verified("", "");
    private volatile IntegrationReport report = IntegrationReport.unknown();
    public ImportedPackIntegration(Provider provider, Path root, String compiledFile) throws IOException {
        this.provider = provider;
        this.root = root.toAbsolutePath().normalize();
        if (!compiledFile.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_.-]+)*\\.zip") || compiledFile.contains("..")) throw new PackProblem(Code.UNSAFE_PATH, "compiled pack");
        source = this.root.resolve(provider.source);
        marker = this.root.resolve(".wellui-import.json");
        compiled = this.root.resolve(compiledFile).normalize();
        if (compiled.startsWith(source.getParent())) throw new PackProblem(Code.UNSAFE_PATH, "compiled pack is an import source");
        safe(source);
        safe(marker);
        safe(compiled);
    }
    public String owner() { return provider.owner; }
    public String sourceRoot() { return provider.source; }
    public String compiledPack() { return root.relativize(compiled).toString().replace('\\', '/'); }
    public IntegrationReport report() { return report; }
    private void safe(Path path) throws IOException {
        if (!path.normalize().startsWith(root)) throw new PackProblem(Code.UNSAFE_PATH, "provider path");
        for (Path next = path; next != null; next = next.getParent()) {
            if (Files.isSymbolicLink(next) || Files.exists(next, LinkOption.NOFOLLOW_LINKS) && !next.toRealPath().equals(next.toAbsolutePath().normalize())) throw new PackProblem(Code.UNSAFE_PATH, "provider path");
        }
    }
    private String hash(Path path, String algorithm) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path) || Files.size(path) > 512L * 1024 * 1024) throw new PackProblem(Code.PROVIDER_OUTPUT_UNREADABLE, compiledPack());
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Source source(boolean required) throws IOException {
        safe(marker);
        safe(source);
        if (!Files.exists(marker)) {
            if (Files.exists(source)) throw new PackProblem(Code.EXTERNAL_NAMESPACE_COLLISION, provider.source);
            if (required) throw new PackProblem(Code.WELLUI_SOURCE_MISSING, provider.source);
            return null;
        }
        if (!Files.isRegularFile(marker) || Files.size(marker) > 2048) throw new PackProblem(Code.SOURCE_MANIFEST_INVALID, ".wellui-import.json");
        try {
            var json = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            if (json.size() != 4 || json.get("version").getAsInt() != 1 || !json.get("provider").getAsString().equals(provider.name())) throw new IllegalArgumentException();
            String generation = json.get("generation").getAsString(), sha256 = json.get("sha256").getAsString();
            if (!generation.matches("[0-9a-f]{64}") || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            return new Source(generation, sha256);
        } catch (RuntimeException invalid) { throw new PackProblem(Code.SOURCE_MANIFEST_INVALID, ".wellui-import.json"); }
    }
    public synchronized void export(Generation generation) throws IOException {
        try {
            ScreenGenerationBuilder.validate(generation.path());
            var prior = source(false);
            if (prior != null && Files.exists(source) && !hash(source, "SHA-256").equals(prior.sha256())) throw new PackProblem(Code.WELLUI_SOURCE_MODIFIED, provider.source);
            String sha = AssetStore.hash("SHA-256", Files.readAllBytes(generation.path()));
            if (!sha.equals(generation.sha256())) throw new IOException("screen.graph_invalid");
            // The generation is outside the provider root. Read only after its strict graph validation.
            if (prior == null || !sha.equals(prior.sha256()) || !Files.exists(source)) {
                Files.createDirectories(source.getParent());
                AtomicFiles.write(source, Files.readAllBytes(generation.path()));
                AtomicFiles.write(marker, new Gson().toJson(Map.of("version", 1, "provider", provider.name(), "generation", generation.sha256(), "sha256", sha)).getBytes(StandardCharsets.UTF_8));
            }
            if (!generation.sha256().equals(verified.generation())) {
                verified = new Verified("", "");
                report = new IntegrationReport(generation.sha256(), generation.sha256(), "UNKNOWN", 1, "OK", "STALE", "UNKNOWN",
                        new PackProblem(Code.PROVIDER_BUILD_REQUIRED, compiledPack()), false);
            }
        } catch (IOException failure) { failed(generation, failure);
            throw report.problem();
        }
    }
    public String compiledStamp() throws IOException {
        safe(compiled);
        if (!Files.exists(compiled)) return "MISSING";
        var attr = Files.readAttributes(compiled, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attr.size() + ":" + attr.lastModifiedTime() + ":" + attr.creationTime() + ":" + attr.fileKey();
    }
    public synchronized String verify(Generation generation) throws IOException {
        try {
            var manifest = source(true);
            if (!Files.exists(source)) throw new PackProblem(Code.WELLUI_SOURCE_MISSING, provider.source);
            if (!hash(source, "SHA-256").equals(manifest.sha256())) throw new PackProblem(Code.WELLUI_SOURCE_MODIFIED, provider.source);
            if (!generation.sha256().equals(manifest.generation())) throw new PackProblem(Code.SOURCE_GENERATION_STALE, provider.source);
            ScreenGenerationBuilder.validate(generation.path());
            Map<String, byte[]> expected = new TreeMap<>();
            try (var zip = new ZipFile(generation.path().toFile())) {
                for (var entry : zip.stream().filter(e -> e.getName().startsWith("assets/wellui/")).toList()) {
                    try (var input = zip.getInputStream(entry)) { expected.put(entry.getName(), input.readAllBytes()); }
                }
            }
            String before = compiledStamp();
            if (before.equals("MISSING")) throw new PackProblem(Code.GENERATED_OUTPUT_NOT_BUILT, compiledPack());
            if (!Files.isRegularFile(compiled) || Files.size(compiled) > 512L * 1024 * 1024) throw new PackProblem(Code.PROVIDER_OUTPUT_UNREADABLE, compiledPack());
            var verifier = new MergedPackVerifier(ScreenGenerationBuilder.profile(generation.path()));
            verifier.verify(compiled, expected);
            String sha1 = hash(compiled, "SHA-1");
            if (!before.equals(compiledStamp())) throw new PackProblem(Code.PROVIDER_OUTPUT_UNREADABLE, compiledPack());
            verified = new Verified(generation.sha256(), sha1);
            report = new IntegrationReport(generation.sha256(), generation.sha256(), generation.sha256(), expected.size(), "OK", "OK", "OK", null, false)
                    .compilation(verifier.models(), verifier.pixels(), verifier.rewrites());
            return sha1;
        } catch (IOException failure) { failed(generation, failure);
            throw report.problem();
        }
    }
    private void failed(Generation generation, IOException failure) {
        verified = new Verified("", "");
        PackProblem problem = failure instanceof PackProblem p ? p : new PackProblem(Code.PROVIDER_OUTPUT_UNREADABLE, compiledPack());
        if (problem.code() == Code.ITEMSADDER_BUILD_REQUIRED) problem = new PackProblem(Code.PROVIDER_BUILD_REQUIRED, problem.resource(), problem.detail());
        if (problem.code() == Code.ITEMSADDER_OUTPUT_UNREADABLE) problem = new PackProblem(Code.PROVIDER_OUTPUT_UNREADABLE, problem.resource(), problem.detail());
        report = new IntegrationReport(generation.sha256(), report.sourceGeneration(), "UNKNOWN", report.files(), problem.conflict() ? "FAILED" : report.source(),
                problem.buildRequired() ? "STALE" : "FAILED", "UNKNOWN", problem, false);
    }
    public String verifiedHash(String generation) { var current = verified;
        return current.generation().equals(generation) ? current.sha1() : "";
    }
}
