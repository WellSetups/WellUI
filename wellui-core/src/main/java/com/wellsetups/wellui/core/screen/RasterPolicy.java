package com.wellsetups.wellui.core.screen;

/** Uniform, bounded sampling of logical coordinates; no intermediate square or resampling pass. */
public record RasterPolicy(Quality quality, int maxDimension, Interpolation images) {
    public enum Quality { AUTO, BALANCED, HIGH, ULTRA }
    public enum Interpolation { AUTO, SMOOTH, PIXELATED }
    public record Size(int width, int height, double scale) { }
    public RasterPolicy {
        if (quality == null || images == null || maxDimension < 256 || maxDimension > 4096) throw new IllegalArgumentException("screen.config");
    }
    public static RasterPolicy defaults() { return new RasterPolicy(Quality.AUTO, 2048, Interpolation.AUTO); }
    public Size size(double width, double height, int frames) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 32 || height < 32 || frames < 1 || frames > 17) {
            throw new IllegalArgumentException("screen.document_limits");
        }
        double worldScale = ScreenPlane.scale(width, height);
        double desired = switch (quality) {
            case AUTO -> Math.min(2, Math.max(1, 640 * worldScale));
            case BALANCED -> 1;
            case HIGH -> 2;
            case ULTRA -> 3;
        };
        // 4M pixels per sprite, 24M across base/hover frames. This is a WellUI budget, not a GPU guarantee.
        double budget = Math.min(4_000_000, 24_000_000.0 / frames);
        double factor = Math.min(desired, Math.min(maxDimension / Math.max(width, height), Math.sqrt(budget / (width * height))));
        return new Size(Math.max(1, (int) Math.floor(width * factor)), Math.max(1, (int) Math.floor(height * factor)), factor);
    }
}
