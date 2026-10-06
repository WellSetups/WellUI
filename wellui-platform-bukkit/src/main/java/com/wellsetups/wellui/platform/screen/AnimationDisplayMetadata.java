package com.wellsetups.wellui.platform.screen;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.wellsetups.wellui.core.screen.ScreenFrame;
import java.util.List;

/** 1.21.11: start is a relative client-tick delay, transformation duration differs from teleport duration. */
final class AnimationDisplayMetadata {
    private AnimationDisplayMetadata() { }
    static List<EntityData<?>> transform(DisplayTransform pose, int ticks) {
        if (ticks < 0 || ticks > 1200) throw new IllegalArgumentException("animation.duration");
        var t = pose.translation();
        return List.of(new EntityData<>(8, EntityDataTypes.INT, 0), new EntityData<>(9, EntityDataTypes.INT, ticks),
                new EntityData<>(10, EntityDataTypes.INT, 0),
                new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f((float) t.x(), (float) t.y(), (float) t.z())),
                new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f((float) pose.scaleX(), (float) pose.scaleY(), 1)),
                new EntityData<>(13, EntityDataTypes.QUATERNION, quaternion(pose.left())),
                new EntityData<>(14, EntityDataTypes.QUATERNION, quaternion(pose.right())));
    }
    private static Quaternion4f quaternion(ScreenFrame.Rotation q) { return new Quaternion4f((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()); }
}
