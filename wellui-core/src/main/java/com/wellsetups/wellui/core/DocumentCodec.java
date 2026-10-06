package com.wellsetups.wellui.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.Layout;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.Style;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiAction;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict, bounded JSON codec with explicit polymorphism and a versioned migration boundary. */
public final class DocumentCodec {
    public static final int MAX_JSON_BYTES = 1_048_576;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final DocumentMigrator migrator = new DocumentMigrator();
    /** Strict envelope parser for bounded multi-project Studio transactions. */
    public JsonElement bundle(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 2 * MAX_JSON_BYTES) throw new IllegalArgumentException("validation.document_size");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            checkStructure(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("validation.json");
            return JsonParser.parseString(json);
        } catch (IOException invalid) { throw new IllegalArgumentException("validation.json", invalid); }
    }

    public UiDocument decode(String json) {
        if (json == null || json.length() > MAX_JSON_BYTES
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("validation.document_size");
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            checkStructure(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("validation.json");
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("validation.document");
            JsonObject root = migrator.migrate(parsed.getAsJsonObject());
            var names = new java.util.ArrayList<>(List.of("schemaVersion", "id", "name", "width", "height", "children"));
            if (root.has("logic")) names.add("logic");
            if (root.has("animations")) names.add("animations");
            if (root.has("controls")) names.add("controls");
            fields(root, names.toArray(String[]::new));
            return new UiDocument(integer(root, "schemaVersion"), string(root, "id"), string(root, "name"),
                    number(root, "width"), number(root, "height"), children(root, 1, new int[1]), com.wellsetups.wellui.core.logic.LogicCodec.decode(root.get("logic")), com.wellsetups.wellui.core.animation.AnimationCodec.decode(root.get("animations")), com.wellsetups.wellui.core.control.ControlCodec.decode(root.get("controls")));
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException("validation.json", exception);
        }
    }

    public String encode(UiDocument document) {
        if (!new DocumentValidator().validate(document).isEmpty()) throw new IllegalArgumentException("validation.document");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", document.schemaVersion());
        root.addProperty("id", document.id());
        root.addProperty("name", document.name());
        root.addProperty("width", document.width());
        root.addProperty("height", document.height());
        root.add("children", encodeChildren(document.children()));
        if (!document.logic().isEmpty()) root.add("logic", com.wellsetups.wellui.core.logic.LogicCodec.encode(document.logic()));
        if (!document.animations().isEmpty()) root.add("animations", com.wellsetups.wellui.core.animation.AnimationCodec.encode(document.animations()));
        if (!document.controls().isEmpty()) root.add("controls", com.wellsetups.wellui.core.control.ControlCodec.encode(document.controls()));
        String result = GSON.toJson(root);
        if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("validation.document_size");
        return result;
    }

    private List<UiComponent> children(JsonObject parent, int depth, int[] count) {
        if (depth > DocumentValidator.MAX_DEPTH) throw new IllegalArgumentException("validation.depth");
        JsonElement element = parent.get("children");
        if (element == null || !element.isJsonArray()) throw new IllegalArgumentException("validation.children");
        List<UiComponent> result = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (++count[0] > DocumentValidator.MAX_COMPONENTS) throw new IllegalArgumentException("validation.component_limit");
            if (!child.isJsonObject()) throw new IllegalArgumentException("validation.component");
            JsonObject node = child.getAsJsonObject();
            String type = string(node, "type");
            String id = string(node, "id");
            Layout layout = layout(object(node, "layout"));
            Style style = style(object(node, "style"));
            switch (type) {
                case "panel" -> {
                    fields(node, "type", "id", "layout", "style", "children");
                    result.add(new PanelComponent(id, layout, style, children(node, depth + 1, count)));
                }
                case "text" -> {
                    fields(node, "type", "id", "layout", "style", "text");
                    result.add(new TextComponent(id, layout, style, string(node, "text")));
                }
                case "image" -> {
                    fields(node, "type", "id", "layout", "style", "asset");
                    result.add(new ImageComponent(id, layout, style, string(node, "asset")));
                }
                case "button" -> {
                    fields(node, "type", "id", "layout", "style", "text", "action");
                    JsonObject action = object(node, "action");
                    fields(action, "type", "value");
                    result.add(new ButtonComponent(id, layout, style, string(node, "text"),
                            new UiAction(string(action, "type"), string(action, "value"))));
                }
                default -> throw new IllegalArgumentException("validation.component_type");
            }
        }
        return List.copyOf(result);
    }

    private Layout layout(JsonObject node) {
        fields(node, "x", "y", "width", "height", "anchor");
        return new Layout(number(node, "x"), number(node, "y"), number(node, "width"), number(node, "height"), string(node, "anchor"));
    }

    private Style style(JsonObject node) {
        fields(node, "background", "color", "opacity", "fontSize");
        return new Style(string(node, "background"), string(node, "color"), number(node, "opacity"), integer(node, "fontSize"));
    }

    private JsonArray encodeChildren(List<UiComponent> children) {
        JsonArray array = new JsonArray();
        for (UiComponent component : children) {
            JsonObject node = new JsonObject();
            node.addProperty("id", component.id());
            node.add("layout", GSON.toJsonTree(component.layout()));
            node.add("style", GSON.toJsonTree(component.style()));
            if (component instanceof PanelComponent panel) {
                node.addProperty("type", "panel");
                node.add("children", encodeChildren(panel.children()));
            } else if (component instanceof TextComponent text) {
                node.addProperty("type", "text");
                node.addProperty("text", text.text());
            } else if (component instanceof ImageComponent image) {
                node.addProperty("type", "image");
                node.addProperty("asset", image.asset());
            } else if (component instanceof ButtonComponent button) {
                node.addProperty("type", "button");
                node.addProperty("text", button.text());
                node.add("action", GSON.toJsonTree(button.action()));
            }
            array.add(node);
        }
        return array;
    }

    private void checkStructure(JsonReader reader, int depth) throws IOException {
        if (depth > 48) throw new IllegalArgumentException("validation.depth");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) throw new IllegalArgumentException("validation.duplicate_field");
                    checkStructure(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) checkStructure(reader, depth + 1);
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException("validation.json");
        }
    }

    private static void fields(JsonObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        if (!object.keySet().equals(names)) throw new IllegalArgumentException("validation.fields");
    }

    private static JsonObject object(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("validation.field." + key);
        return value.getAsJsonObject();
    }

    private static String string(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("validation.field." + key);
        return value.getAsString();
    }

    private static double number(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("validation.field." + key);
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("validation.field." + key);
        return result;
    }

    private static int integer(JsonObject source, String key) {
        double value = number(source, key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("validation.field." + key);
        return (int) value;
    }
}
