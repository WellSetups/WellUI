package com.wellsetups.wellui.core.screen;

/** Session orientation is fixed; only the eye-origin translates. No player property is changed. */
public record ScreenFrame(Vec3 origin, Vec3 forward, ScreenPlane reference, float yaw, float pitch, double distance) {
    public static final double CLOSE_ANGLE_COSINE = .25;
    public record Rotation(double x, double y, double z, double w) {
        public Vec3 apply(Vec3 v) {
            Vec3 q = new Vec3(x, y, z);
            Vec3 t = q.cross(v).multiply(2);
            return v.add(t.multiply(w)).add(q.cross(t));
        }
    }
    public static Rotation rotation(float yaw, float pitch) {
        double y = Math.toRadians(-yaw) / 2;
        double p = Math.toRadians(pitch) / 2;
        return new Rotation(Math.cos(y) * Math.sin(p), Math.sin(y) * Math.cos(p),
                -Math.sin(y) * Math.sin(p), Math.cos(y) * Math.cos(p));
    }
    /** Minecraft 1.21.11 applies Y(+pi) after T * left * scale * right for ItemDisplay. Cancel it on the right. */
    public static Rotation itemDisplayCompensation() { return new Rotation(0, -1, 0, 0); }
    public static ScreenFrame create(Vec3 eye, float yaw, float pitch, double width, double height) {
        return create(eye, yaw, pitch, width, height, ScreenPlane.DEFAULT_DISTANCE);
    }
    public static ScreenFrame create(Vec3 eye, float yaw, float pitch, double width, double height, double distance) {
        return new ScreenFrame(eye, Vec3.look(yaw, pitch), ScreenPlane.create(eye, yaw, pitch, width, height, distance), yaw, pitch, distance);
    }
    public ScreenPlane at(Vec3 eye) {
        return new ScreenPlane(eye.add(forward.multiply(distance)), reference.right(), reference.up(), reference.normal(),
                reference.width(), reference.height(), reference.referenceWidth(), reference.referenceHeight());
    }
    public boolean outside(float yaw, float pitch) { return forward.dot(Vec3.look(yaw, pitch)) < CLOSE_ANGLE_COSINE; }
}
