package com.wellsetups.wellui.platform.screen;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.wellsetups.wellui.core.screen.ScreenFrame;
import java.util.List;

/** Exact 1.21.11 display transform; shared by canvas/cursor and tested at the packet metadata boundary. */
final class ItemDisplayMetadata {
    private ItemDisplayMetadata() { }
    static List<EntityData<?>> transform(double width, double height, float yaw, float pitch) {
        var left = ScreenFrame.rotation(yaw, pitch);
        var right = ScreenFrame.itemDisplayCompensation();
        // Fixed entity orientation=0, no billboard, positive scale. Vanilla applies its item Y(+pi) afterward.
        return List.of(
                new EntityData<>(5, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(8, EntityDataTypes.INT, 0),
                new EntityData<>(9, EntityDataTypes.INT, 0),
                new EntityData<>(10, EntityDataTypes.INT, 0),
                new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(0, 0, 0)),
                new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f((float) width, (float) height, 1)),
                new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f((float) left.x(), (float) left.y(), (float) left.z(), (float) left.w())),
                new EntityData<>(14, EntityDataTypes.QUATERNION, new Quaternion4f((float) right.x(), (float) right.y(), (float) right.z(), (float) right.w())),
                new EntityData<>(15, EntityDataTypes.BYTE, (byte) 0),
                new EntityData<>(16, EntityDataTypes.INT, (15 << 4) | (15 << 20)),
                new EntityData<>(17, EntityDataTypes.FLOAT, 1.0f),
                new EntityData<>(18, EntityDataTypes.FLOAT, 0.0f),
                new EntityData<>(24, EntityDataTypes.BYTE, (byte) 0));
    }
}
