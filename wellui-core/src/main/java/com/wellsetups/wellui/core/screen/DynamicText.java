package com.wellsetups.wellui.core.screen;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Bounded, fixed-advance bitmap font for packet text. Resource generation never runs during play. */
public final class DynamicText {
    public static final String FONT = "wellui:logic";
    public static final String FONT_PATH = "assets/wellui/font/logic.json";
    public static final String TEXTURE_PATH = "assets/wellui/textures/font/logic.png";
    private static final String GLYPHS = glyphs();
    public record Block(String text, int columns, int rows) { }
    private DynamicText() { }
    private static String glyphs() {
        var value = new StringBuilder();
        for (char c = 33; c <= 255; c++) if (!Character.isISOControl(c)) value.append(c);
        value.append("ĞğİıŞş");
        return value.toString();
    }
    public static Block layout(String value, double width, double height, int fontSize) {
        int columns = Math.max(1, Math.min(128, (int) Math.floor((width - 2 * fontSize / 10.0) / (8 * fontSize / 10.0))));
        int rows = Math.max(0, Math.min(16, (int) Math.floor(height / fontSize)));
        if (rows == 0) return new Block("", 0, 0);
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        int consumed = 0;
        for (int code : value.codePoints().toArray()) {
            if (++consumed > 512 || lines.size() >= rows) break;
            if (code == '\n') { lines.add(line.toString());
                line.setLength(0);
                continue;
                }
            if (line.length() == columns) { lines.add(line.toString());
                line.setLength(0);
                if (lines.size() >= rows) break;
                }
            line.append(code == ' ' || GLYPHS.indexOf(code) >= 0 ? (char) code : '?');
        }
        if (lines.size() < rows) lines.add(line.toString());
        return new Block(String.join("\n", lines), lines.stream().mapToInt(String::length).max().orElse(0), lines.size());
    }
    public static Map<String, byte[]> resources() throws IOException {
        int rows = (GLYPHS.length() + 15) / 16;
        var image = new BufferedImage(128, rows * 8, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.setColor(Color.WHITE);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < 16; column++) {
                    int index = row * 16 + column;
                    char c = index < GLYPHS.length() ? GLYPHS.charAt(index) : 0;
                    if (c == 0) continue;
                    g.setClip(column * 8, row * 8, 6, 8);
                    g.drawString(String.valueOf(c), column * 8, row * 8 + 7);
                    // Nonzero alpha fixes every glyph width to seven pixels (vanilla adds one advance pixel).
                    image.setRGB(column * 8 + 6, row * 8 + 7, 0x01ffffff);
                }
            }
        } finally { g.dispose();
            }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Map.of(FONT_PATH, definition().getBytes(StandardCharsets.UTF_8), TEXTURE_PATH, bytes.toByteArray());
    }
    public static int textureHeight() { return ((GLYPHS.length() + 15) / 16) * 8; }
    /** Sorted JSON keys make the font definition identical across JVM processes. */
    public static String definition() {
        var chars = new ArrayList<String>();
        for (int row = 0; row < textureHeight() / 8; row++) {
            var line = new StringBuilder();
            for (int column = 0; column < 16; column++) {
                int index = row * 16 + column;
                line.append(index < GLYPHS.length() ? GLYPHS.charAt(index) : (char) 0);
            }
            chars.add(line.toString());
        }
        var definition = Map.of("providers", List.of(new TreeMap<>(Map.of("type", "space", "advances", Map.of(" ", 8))),
                new TreeMap<>(Map.of("type", "bitmap", "file", "wellui:font/logic.png", "height", 8, "ascent", 7, "chars", chars))));
        return new Gson().toJson(definition);
    }
}
