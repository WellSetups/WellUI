package com.wellsetups.wellui.core.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wellsetups.wellui.api.document.UiControl;
import com.wellsetups.wellui.api.logic.LogicType;
import com.wellsetups.wellui.api.logic.LogicValue;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict optional schema-1 extension. Missing controls preserve legacy documents exactly. */
public final class ControlCodec {
    private ControlCodec() { }
    public static JsonElement encode(Map<String, UiControl> controls) {
        var result = new JsonObject();
        var gson = new com.google.gson.Gson();
        new TreeMap<>(controls).forEach((id, control) -> {
            var node = gson.toJsonTree(control).getAsJsonObject();
            node.add("settings", gson.toJsonTree(new TreeMap<>(control.settings())));
            result.add(id, node);
        });
        return result;
    }
    public static Map<String, UiControl> decode(JsonElement value) {
        if (value == null) return Map.of();
        try {
            var root = value.getAsJsonObject();
            if (root.size() > 64) throw new IllegalArgumentException();
            var result = new TreeMap<String, UiControl>();
            for (var entry : root.entrySet()) {
                var node = entry.getValue().getAsJsonObject();
                fields(node, "kind", "settings", "options");
                var settings = new TreeMap<String, LogicValue>();
                if (node.getAsJsonObject("settings").size() > 24 || node.getAsJsonArray("options").size() > 32) throw new IllegalArgumentException();
                for (var setting : node.getAsJsonObject("settings").entrySet()) {
                    var scalar = setting.getValue().getAsJsonObject();
                    fields(scalar, "type", "value");
                    settings.put(setting.getKey(), new LogicValue(LogicType.valueOf(text(scalar, "type")), text(scalar, "value")));
                }
                var options = new ArrayList<UiControl.Option>();
                for (var element : node.getAsJsonArray("options")) {
                    var option = element.getAsJsonObject();
                    fields(option, "value", "label", "disabled");
                    if (!option.get("disabled").isJsonPrimitive() || !option.getAsJsonPrimitive("disabled").isBoolean()) throw new IllegalArgumentException();
                    options.add(new UiControl.Option(text(option, "value"), text(option, "label"), option.get("disabled").getAsBoolean()));
                }
                result.put(entry.getKey(), new UiControl(UiControl.Kind.valueOf(text(node, "kind")), settings, options));
            }
            return Map.copyOf(result);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("control.json", invalid); }
    }
    private static String text(JsonObject node, String key) {
        if (!node.get(key).isJsonPrimitive() || !node.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
        return node.get(key).getAsString();
    }
    private static void fields(JsonObject node, String... keys) {
        if (!node.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
    }
}
