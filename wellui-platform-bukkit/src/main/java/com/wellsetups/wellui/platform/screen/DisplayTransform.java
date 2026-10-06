package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.core.animation.Affine2;
import com.wellsetups.wellui.core.screen.Vec3;
import com.wellsetups.wellui.core.screen.ScreenFrame;

/** Protocol-independent Display pose. Two rotations preserve hierarchical nonuniform scale/shear. */
public record DisplayTransform(Vec3 translation, double scaleX, double scaleY, ScreenFrame.Rotation left, ScreenFrame.Rotation right) {
    public static DisplayTransform of(Vec3 translation, Affine2 matrix, double width, double height, float yaw, float pitch) {
        double a = matrix.a() * width, b = matrix.b() * width, c = matrix.c() * height, d = matrix.d() * height;
        double aa = a * a + b * b, cc = c * c + d * d, ac = a * c + b * d;
        // Orthogonal columns already have a stable rotation/scale decomposition. Sorting singular
        // values here would rotate both axes by 90 degrees when animated width crosses height.
        if (Math.abs(ac) <= 1e-12 * Math.sqrt(aa * cc)) {
            return new DisplayTransform(translation, Math.sqrt(aa), Math.sqrt(cc),
                    multiply(ScreenFrame.rotation(yaw, pitch), z(Math.atan2(b, a))), ScreenFrame.itemDisplayCompensation());
        }
        double phi = .5 * Math.atan2(2 * ac, aa - cc);
        double cos = Math.cos(phi), sin = Math.sin(phi);
        double ux = a * cos + c * sin, uy = b * cos + d * sin;
        double first = Math.hypot(ux, uy), second = (a * d - b * c) / first;
        double angle = Math.atan2(uy, ux);
        return new DisplayTransform(translation, first, second, multiply(ScreenFrame.rotation(yaw, pitch), z(angle)),
                multiply(z(-phi), ScreenFrame.itemDisplayCompensation()));
    }
    private static ScreenFrame.Rotation z(double angle) { return new ScreenFrame.Rotation(0, 0, Math.sin(angle / 2), Math.cos(angle / 2)); }
    private static ScreenFrame.Rotation multiply(ScreenFrame.Rotation q, ScreenFrame.Rotation r) {
        return new ScreenFrame.Rotation(q.w() * r.x() + q.x() * r.w() + q.y() * r.z() - q.z() * r.y(),
                q.w() * r.y() - q.x() * r.z() + q.y() * r.w() + q.z() * r.x(),
                q.w() * r.z() + q.x() * r.y() - q.y() * r.x() + q.z() * r.w(),
                q.w() * r.w() - q.x() * r.x() - q.y() * r.y() - q.z() * r.z());
    }
}
