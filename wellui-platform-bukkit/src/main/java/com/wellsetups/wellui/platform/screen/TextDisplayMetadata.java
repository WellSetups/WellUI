package com.wellsetups.wellui.platform.screen;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.wellsetups.wellui.core.screen.DynamicText;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** Exact 1.21.11 TextDisplay metadata; fixed geometry, no clickable/interactive text components. */
final class TextDisplayMetadata {
    private TextDisplayMetadata() { }
    static List<EntityData<?>> transform(double scale, float yaw, float pitch) {
        var data = new ArrayList<>(ItemDisplayMetadata.transform(scale, scale, yaw, pitch));
        data.removeIf(value -> value.getIndex() == 12 || value.getIndex() == 24);
        data.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f((float) scale, (float) scale, (float) scale)));
        // TextDisplay applies Y(pi) then uniform -0.025 before drawing. Retain the right Y(-pi)
        // so pixel +X maps to the SCREEN right axis and pixel +Y maps down.
        data.add(new EntityData<>(24, EntityDataTypes.INT, 100000));
        data.add(new EntityData<>(25, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(27, EntityDataTypes.BYTE, (byte) 8));
        return data;
    }
    static List<EntityData<?>> content(String value, String color, double opacity) {
        // Values 0..3 have special fallback behavior in vanilla: hidden text must be empty.
        var text = Component.text(opacity <= 0 ? "" : value).font(Key.key(DynamicText.FONT)).color(TextColor.fromHexString(color));
        return List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(26, EntityDataTypes.BYTE, (byte) Math.max(4, Math.min(255, Math.round(opacity * 255)))));
    }
}
