package com.wellsetups.wellui.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** PNG-only content-addressed storage. Metadata and trailing input are stripped by re-encoding. */
public final class AssetStore {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final Path root;
    private final int maxBytes;

    public AssetStore(Path root, int maxBytes) throws IOException {
        if (maxBytes < 1 || maxBytes > 8 * 1024 * 1024) throw new IllegalArgumentException("asset.limit");
        this.root = AtomicFiles.directory(root);
        this.maxBytes = maxBytes;
    }

    public int maxBytes() { return maxBytes; }

    public synchronized String put(byte[] png) throws IOException {
        if (png == null || png.length > maxBytes || png.length < 24
                || !Arrays.equals(SIGNATURE, Arrays.copyOf(png, 8))) throw new IllegalArgumentException("asset.png");
        BufferedImage decoded;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("asset.png");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 2048 || height > 2048) throw new IllegalArgumentException("asset.dimensions");
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException malformed) {
            throw new IllegalArgumentException("asset.png", malformed);
        }
        BufferedImage normalized = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
        normalized.setRGB(0, 0, decoded.getWidth(), decoded.getHeight(),
                decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth()), 0, decoded.getWidth());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(normalized, "png", output)) throw new IOException("asset.encoder");
        byte[] bytes = output.toByteArray();
        if (bytes.length > maxBytes) throw new IllegalArgumentException("asset.limit");
        String hash = hash("SHA-256", bytes);
        if (!exists(hash)) AtomicFiles.write(path(hash), bytes);
        return hash;
    }

    public byte[] read(String hash) throws IOException {
        Path file = path(hash);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("asset.missing");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes || !hash("SHA-256", bytes).equals(hash)) throw new IOException("asset.integrity");
            return bytes;
        }
    }

    public boolean exists(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}") && Files.isRegularFile(path(hash), LinkOption.NOFOLLOW_LINKS);
    }

    private Path path(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("validation.asset");
        return root.resolve(hash + ".png");
    }
    public record Info(String id, String name, int width, int height, long bytes) { }
    public synchronized String put(byte[] png, String name) throws IOException {
        if (name == null || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("asset.name");
        String id = put(png);
        AtomicFiles.write(root.resolve(id + ".name"), name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return id;
    }
    public synchronized java.util.List<Info> list() throws IOException {
        var result = new java.util.ArrayList<Info>();
        try (var entries = Files.list(root)) {
            for (Path file : entries.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.png")).sorted().limit(2049).toList()) {
                if (result.size() == 2048) throw new IOException("asset.limit");
                String id = file.getFileName().toString().substring(0, 64);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("asset.missing");
                long size = Files.size(file);
                byte[] png;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { png = input.readNBytes(24); }
                if (size > maxBytes || png.length < 24 || !Arrays.equals(SIGNATURE, Arrays.copyOf(png, 8))) throw new IOException("asset.integrity");
                var header = java.nio.ByteBuffer.wrap(png);
                Path label = root.resolve(id + ".name");
                String name = "Image " + id.substring(0, 8);
                if (Files.isRegularFile(label, LinkOption.NOFOLLOW_LINKS)) {
                    try (var input = Files.newInputStream(label, LinkOption.NOFOLLOW_LINKS)) {
                        name = new String(input.readNBytes(512), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                result.add(new Info(id, name, header.getInt(16), header.getInt(20), size));
            }
        }
        return java.util.List.copyOf(result);
    }
    /** Caller must hold project/deployment locks and prove no draft, runtime or retained revision uses this asset. */
    public synchronized void deleteUnused(String hash) throws IOException {
        Files.deleteIfExists(path(hash));
        Files.deleteIfExists(root.resolve(hash + ".name"));
    }

    public static String hash(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
