package com.wellsetups.wellui.core.animation;

import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.core.DocumentValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AnimationValidator {
    public static final int MAX_ANIMATIONS = 32, MAX_TRACKS = 128, MAX_KEYS = 2048, MAX_TRACK_KEYS = 64, MAX_DURATION = 1200, MAX_ACTIVE = 8;
    public List<DocumentValidator.ValidationIssue> validate(UiDocument document) {
        var issues = new ArrayList<DocumentValidator.ValidationIssue>();
        var definitions = document.animations();
        if (definitions.schemaVersion() != 1) issue(issues, "schema", "");
        if (definitions.items().size() > MAX_ANIMATIONS) { issue(issues, "limit", "");
            return issues;
        }
        var components = new HashSet<String>();
        collect(document.children(), components);
        var ids = new HashSet<String>();
        int tracks = 0, keys = 0;
        for (var a : definitions.items()) {
            if (!DocumentValidator.validId(a.id()) || !ids.add(a.id())) issue(issues, "id", a.id());
            if (a.name().isBlank() || a.name().length() > 128 || a.name().codePoints().anyMatch(Character::isISOControl)) issue(issues, "name", a.id());
            if (a.duration() < 1 || a.duration() > MAX_DURATION) issue(issues, "duration", a.id());
            if (a.tracks().isEmpty()) issue(issues, "tracks", a.id());
            var owned = new HashSet<String>();
            tracks += a.tracks().size();
            for (var t : a.tracks()) {
                if (!components.contains(t.component())) issue(issues, "component", t.component());
                if (!owned.add(t.component() + ":" + t.property())) issue(issues, "track_duplicate", a.id());
                keys += t.keyframes().size();
                if (t.keyframes().isEmpty() || t.keyframes().size() > MAX_TRACK_KEYS) issue(issues, "keyframes", a.id());
                int previous = -1;
                for (var k : t.keyframes()) {
                    if (k.tick() <= previous || k.tick() > a.duration() || k.tick() < 0) issue(issues, "time", a.id());
                    if (!t.property().valid(k.value())) issue(issues, "value", a.id());
                    previous = k.tick();
                }
            }
        }
        if (tracks > MAX_TRACKS || keys > MAX_KEYS) issue(issues, "limit", "");
        if (!definitions.isEmpty() && AnimationVisuals.independent(document).size() > 16) issue(issues, "screen_layers", "");
        if (!definitions.isEmpty()) bounds(document.children(), 0, 0, document.width(), document.height(), document.width(), document.height(),
                AnimationVisuals.independent(document), issues);
        return List.copyOf(issues);
    }
    private void bounds(List<UiComponent> nodes, double ox, double oy, double pw, double ph, double width, double height,
                        Set<String> independent, List<DocumentValidator.ValidationIssue> issues) {
        for (var n : nodes) {
            var l = n.layout();
            double x = ox + l.x() + (l.anchor().endsWith("right") ? pw - l.width() : l.anchor().equals("center") ? (pw - l.width()) / 2 : 0);
            double y = oy + l.y() + (l.anchor().startsWith("bottom") ? ph - l.height() : l.anchor().equals("center") ? (ph - l.height()) / 2 : 0);
            if (independent.contains(n.id()) && (x < 0 || y < 0 || x + l.width() > width || y + l.height() > height)) issue(issues, "bounds", n.id());
            if (n instanceof PanelComponent p) bounds(p.children(), x, y, l.width(), l.height(), width, height, independent, issues);
        }
    }
    private void collect(List<UiComponent> nodes, Set<String> ids) {
        for (var n : nodes) { ids.add(n.id());
            if (n instanceof PanelComponent p) collect(p.children(), ids);
        }
    }
    private void issue(List<DocumentValidator.ValidationIssue> issues, String code, String id) {
        issues.add(new DocumentValidator.ValidationIssue("animation." + code, id));
    }
}
