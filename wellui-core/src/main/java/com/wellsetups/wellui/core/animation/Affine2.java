package com.wellsetups.wellui.core.animation;

/** Column-vector affine transform in reference pixels, Y down. */
public record Affine2(double a, double b, double c, double d, double x, double y) {
    public static final Affine2 IDENTITY = new Affine2(1, 0, 0, 1, 0, 0);
    public record Point(double x, double y) { }
    public Affine2 multiply(Affine2 o) {
        return new Affine2(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d,
                a * o.x + c * o.y + x, b * o.x + d * o.y + y);
    }
    public Point point(double px, double py) { return new Point(a * px + c * py + x, b * px + d * py + y); }
    public Point inverse(double px, double py) {
        double determinant = a * d - b * c;
        if (Math.abs(determinant) < 1e-12) return new Point(Double.NaN, Double.NaN);
        return new Point((d * (px - x) - c * (py - y)) / determinant, (-b * (px - x) + a * (py - y)) / determinant);
    }
    public static Affine2 local(double cx, double cy, double tx, double ty, double sx, double sy, double degrees) {
        double cos = Math.cos(Math.toRadians(degrees)), sin = Math.sin(Math.toRadians(degrees));
        double a = cos * sx, b = sin * sx, c = -sin * sy, d = cos * sy;
        return new Affine2(a, b, c, d, cx + tx - a * cx - c * cy, cy + ty - b * cx - d * cy);
    }
}
