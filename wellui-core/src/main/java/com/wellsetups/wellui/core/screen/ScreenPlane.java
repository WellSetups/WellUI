package com.wellsetups.wellui.core.screen;

import java.util.Optional;

/** Viewer-facing normal, logical +X right and +Y down. right cross up = normal. */
public record ScreenPlane(Vec3 center, Vec3 right, Vec3 up, Vec3 normal,
                          double width, double height, double referenceWidth, double referenceHeight) {
    public static final double DEFAULT_DISTANCE = 2;
    public record Cursor(double x, double y, boolean inside, Vec3 intersection) { }
    public static ScreenPlane create(Vec3 eye, double yaw, double pitch, double referenceWidth, double referenceHeight) {
        return create(eye, yaw, pitch, referenceWidth, referenceHeight, DEFAULT_DISTANCE);
    }
    public static ScreenPlane create(Vec3 eye, double yaw, double pitch, double referenceWidth, double referenceHeight, double distance) {
        if (!Double.isFinite(referenceWidth) || !Double.isFinite(referenceHeight) || referenceWidth <= 0 || referenceHeight <= 0) {
            throw new IllegalArgumentException("screen.geometry");
        }
        if (!Double.isFinite(distance) || distance < 1 || distance > 6) throw new IllegalArgumentException("screen.geometry");
        Vec3 forward = Vec3.look(yaw, pitch);
        Vec3 right = new Vec3(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
        double scale = scale(referenceWidth, referenceHeight);
        return new ScreenPlane(eye.add(forward.multiply(distance)), right, right.cross(forward), forward.multiply(-1),
                referenceWidth * scale, referenceHeight * scale, referenceWidth, referenceHeight);
    }
    public static double scale(double width, double height) { return Math.min(3 / width, 1.8 / height); }
    public ScreenPlane translated(Vec3 offset) {
        return new ScreenPlane(center.add(offset), right, up, normal, width, height, referenceWidth, referenceHeight);
    }
    public Optional<Cursor> intersect(Vec3 origin, Vec3 ray) {
        double denominator = ray.dot(normal);
        if (!Double.isFinite(denominator) || denominator >= -1e-8 || origin.subtract(center).dot(normal) <= 0) return Optional.empty();
        double distance = center.subtract(origin).dot(normal) / denominator;
        if (!Double.isFinite(distance) || distance <= 0) return Optional.empty();
        Vec3 intersection = origin.add(ray.multiply(distance));
        Vec3 offset = intersection.subtract(center);
        double u = .5 + offset.dot(right) / width;
        double v = .5 - offset.dot(up) / height;
        boolean inside = u >= 0 && u < 1 && v >= 0 && v < 1;
        return Optional.of(new Cursor(Math.max(0, Math.min(1, u)) * referenceWidth,
                Math.max(0, Math.min(1, v)) * referenceHeight, inside, intersection));
    }
    public Vec3 point(double x, double y, double towardViewer) {
        return center.add(right.multiply((x / referenceWidth - .5) * width))
                .add(up.multiply((.5 - y / referenceHeight) * height)).add(normal.multiply(towardViewer));
    }
}
