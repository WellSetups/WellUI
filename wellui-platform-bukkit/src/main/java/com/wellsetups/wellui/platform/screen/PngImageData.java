package com.wellsetups.wellui.platform.screen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Narrow fallback for PNG checksum protection: identical image data is still required. No files are rewritten. */
final class PngImageData {
    private record Data(byte[] nonImageChunks, byte[] scanlines) { }
    private PngImageData() { }
    static boolean identical(byte[] source, byte[] compiled) throws IOException {
        var expected = data(source);
        var actual = data(compiled);
        return Arrays.equals(expected.nonImageChunks(), actual.nonImageChunks()) && Arrays.equals(expected.scanlines(), actual.scanlines());
    }
    private static Data data(byte[] png) throws IOException {
        var idat = new ByteArrayOutputStream();
        var other = new ByteArrayOutputStream();
        int offset = 8;
        boolean ended = false;
        while (offset <= png.length - 12) {
            int size = ByteBuffer.wrap(png, offset, 4).getInt();
            if (size < 0 || size > png.length - offset - 12) throw new IOException("PNG chunk bound");
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("IDAT")) idat.write(png, offset + 8, size);
            else other.write(png, offset, size + 8); // Preserve length/type/data, excluding only CRC.
            offset += size + 12;
            if (type.equals("IEND")) {
                ended = true;
                break;
            }
        }
        if (!ended || offset != png.length) throw new IOException("PNG end");
        byte[] compressed = idat.toByteArray();
        if (compressed.length < 6 || (compressed[0] & 15) != 8 || (compressed[0] & 255) >> 4 > 7
                || (compressed[1] & 32) != 0 || (((compressed[0] & 255) << 8) + (compressed[1] & 255)) % 31 != 0) throw new IOException("PNG zlib header");
        var inflater = new Inflater(true);
        try {
            inflater.setInput(compressed, 2, compressed.length - 6); // Only checksum/header are outside the exact deflate stream.
            var decoded = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (decoded.size() + count > 64 * 1024 * 1024 || count == 0 && !inflater.finished()) throw new IOException("PNG inflation bound");
                decoded.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) throw new IOException("PNG trailing deflate data");
            return new Data(other.toByteArray(), decoded.toByteArray());
        } catch (DataFormatException invalid) { throw new IOException("PNG deflate", invalid); }
        finally { inflater.end(); }
    }
}
