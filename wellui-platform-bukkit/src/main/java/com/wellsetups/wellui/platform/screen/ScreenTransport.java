package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.core.screen.ScreenInput;
import com.wellsetups.wellui.core.screen.Vec3;
import java.util.UUID;
import org.bukkit.entity.Player;

/** All packet-library linkage is behind this internal platform boundary. */
public interface ScreenTransport extends AutoCloseable {
    interface Viewer {
        String clientVersion();
        int allocateEntityId();
        void listen(ScreenInput input);
        void unlisten(ScreenInput input);
        void spawn(int id, Vec3 center, double width, double height, float yaw, float pitch, String model);
        void model(int id, String model);
        default void input(com.wellsetups.wellui.core.control.TextCapture.Request request) { throw new UnsupportedOperationException("control.input_transport"); }
        default void clearInput() { }
        default void textSpawn(int id, Vec3 center, double scale, float yaw, float pitch, String value, String color, double opacity) {
            throw new UnsupportedOperationException("logic.text_transport");
        }
        default void text(int id, String value, String color, double opacity) {
            throw new UnsupportedOperationException("logic.text_transport");
        }
        default void transform(int id, DisplayTransform pose, int ticks) { throw new UnsupportedOperationException("animation.transport"); }
        void move(int id, Vec3 center);
        void destroy(int... ids);
        void removePack(UUID id);
    }
    Viewer viewer(Player player);
    long sends();
    @Override void close();
}
