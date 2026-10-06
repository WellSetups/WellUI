package com.wellsetups.wellui.core.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wellsetups.wellui.api.animation.UiAnimation;
import com.wellsetups.wellui.api.animation.UiAnimationTrack;
import com.wellsetups.wellui.api.animation.UiAnimations;
import com.wellsetups.wellui.api.animation.UiAnimatedProperty;
import com.wellsetups.wellui.api.animation.UiKeyframe;
import java.util.ArrayList;
import java.util.Set;

/** Strict shape and scalar codec; semantic validation is shared with Java-created definitions. */
public final class AnimationCodec {
    private AnimationCodec() { }
    public static UiAnimations decode(JsonElement json) {
        if (json == null) return UiAnimations.empty();
        try {
            var root = object(json, "schemaVersion", "items");
            var items = new ArrayList<UiAnimation>();
            for (var element : array(root, "items", 32)) {
                var a = object(element, "id", "name", "duration", "loop", "end", "tracks");
                var tracks = new ArrayList<UiAnimationTrack>();
                for (var value : array(a, "tracks", 128)) {
                    var t = object(value, "component", "property", "keyframes");
                    var keys = new ArrayList<UiKeyframe>();
                    for (var frame : array(t, "keyframes", 64)) {
                        var k = object(frame, "tick", "value", "easing");
                        keys.add(new UiKeyframe(integer(k, "tick"), number(k, "value"), UiKeyframe.Easing.valueOf(string(k, "easing"))));
                    }
                    tracks.add(new UiAnimationTrack(string(t, "component"), UiAnimatedProperty.valueOf(string(t, "property")), keys));
                }
                items.add(new UiAnimation(string(a, "id"), string(a, "name"), integer(a, "duration"),
                        UiAnimation.Loop.valueOf(string(a, "loop")), UiAnimation.End.valueOf(string(a, "end")), tracks));
            }
            return new UiAnimations(integer(root, "schemaVersion"), items);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("animation.json", invalid); }
    }
    public static JsonElement encode(UiAnimations definitions) {
        // Records have a stable declaration order; lists preserve explicit author order.
        return new com.google.gson.Gson().toJsonTree(definitions);
    }
    private static JsonObject object(JsonElement value, String... fields) {
        if (!value.isJsonObject() || !value.getAsJsonObject().keySet().equals(Set.of(fields))) throw new IllegalArgumentException();
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject o, String key, int limit) {
        if (!o.get(key).isJsonArray() || o.getAsJsonArray(key).size() > limit) throw new IllegalArgumentException();
        return o.getAsJsonArray(key);
    }
    private static String string(JsonObject o, String key) {
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
        return o.get(key).getAsString();
    }
    private static double number(JsonObject o, String key) {
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException();
        double n = o.get(key).getAsDouble();
        if (!Double.isFinite(n)) throw new IllegalArgumentException();
        return n;
    }
    private static int integer(JsonObject o, String key) {
        double n = number(o, key);
        if (n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException();
        return (int) n;
    }
}
