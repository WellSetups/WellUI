package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.core.screen.ScreenScene;
import com.wellsetups.wellui.core.screen.Vec3;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/** At most eight reusable visible option rows, above content and below the cursor. Prepared textures only. */
final class ScreenOverlay {
    private static final class Row {
        final int id;
        String model;
        Vec3 position;
        Row(int id, String model, Vec3 position) { this.id = id;
            this.model = model;
            this.position = position;
        }
    }
    private final ScreenRenderer.Session session;
    private final Map<String, ScreenPackBuilder.Layer> prepared;
    private final ArrayList<Row> rows = new ArrayList<>();
    private String component = "";
    ScreenOverlay(ScreenRenderer.Session session) {
        this.session = session;
        prepared = session.pack.layers().stream().filter(l -> l.component().startsWith("@popup-"))
                .collect(Collectors.toUnmodifiableMap(l -> l.component().substring(7), l -> l));
    }
    void refresh(ScreenScene scene, double x, double y) {
        var popup = session.router.popup(scene);
        if (popup == null || !prepared.containsKey(popup.component())) { close();
            return;
        }
        if (!component.equals(popup.component())) { close();
            component = popup.component();
        }
        var layer = prepared.get(component);
        int hover = popup.option(x, y);
        int alpha = Math.max(0, Math.min(8, (int) Math.round(scene.opacity(component) * 8)));
        for (int i = 0; i < popup.rows(); i++) {
            int option = popup.first() + i;
            String model = layer.variants().get("option:" + option + ":" + (option == hover ? "hover" : "normal")).get(alpha);
            Vec3 position = session.plane.point(popup.bounds().x() + popup.bounds().width() / 2,
                    popup.bounds().y() + popup.rowHeight() * (i + .5), .012);
            if (rows.size() <= i) {
                var row = new Row(session.viewer.allocateEntityId(), model, position);
                rows.add(row);
                double scale = session.plane.width() / session.document.width();
                session.viewer.spawn(row.id, position, layer.width() * scale, layer.height() * scale, session.yaw, session.pitch, model);
            } else {
                var row = rows.get(i);
                if (!row.position.equals(position)) { session.viewer.move(row.id, position);
                    row.position = position;
                }
                if (!row.model.equals(model)) { session.viewer.model(row.id, model);
                    row.model = model;
                }
            }
        }
    }
    void close() {
        if (!rows.isEmpty()) session.viewer.destroy(rows.stream().mapToInt(r -> r.id).toArray());
        rows.clear();
        component = "";
    }
}
