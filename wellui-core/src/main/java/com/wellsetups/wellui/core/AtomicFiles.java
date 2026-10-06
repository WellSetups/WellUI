package com.wellsetups.wellui.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes within an application-owned directory, with atomic replacement where supported. */
public final class AtomicFiles {
    private AtomicFiles() { }

    public static Path directory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized)) throw new IOException("storage.symlink");
        return normalized.toRealPath();
    }

    public static void write(Path destination, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(destination)) throw new IOException("storage.symlink");
        Path temporary = Files.createTempFile(destination.getParent(), ".wellui-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
