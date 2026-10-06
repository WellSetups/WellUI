package com.wellsetups.wellui.platform.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import static com.wellsetups.wellui.platform.screen.PackProblem.Code;

/** Bounded projection for WellUI's plain model, ItemDisplay context NONE and untinted screen planes. */
final class RenderingSemantics {
    @FunctionalInterface interface Resources { byte[] read(String name) throws IOException; }
    @FunctionalInterface interface Sprites { String resolve(String sprite) throws IOException; }
    private final Resources resources;
    private final Sprites sprites;
    RenderingSemantics(Resources resources, Sprites sprites) {
        this.resources = resources;
        this.sprites = sprites;
    }

    Map<String, String> item(byte[] data, String name) throws IOException {
        var root = json(data, name);
        var model = root.getAsJsonObject("model");
        var result = new TreeMap<String, String>();
        String type = location(model.get("type").getAsString(), name);
        result.put("model.type", type);
        if (!type.equals("minecraft:model")) return result; // Different selection graph cannot equal our plain model.
        result.put("model.reference", location(model.get("model").getAsString(), name));
        // WellUI models have tintindex=-1. Tints are not consumed; geometry verification enforces this.
        return result;
    }
    Map<String, String> model(byte[] data, String name) throws IOException {
        var chain = new java.util.ArrayList<JsonObject>();
        var seen = new HashSet<String>();
        String current = name;
        while (true) {
            if (!seen.add(current) || chain.size() >= 32) throw unreadable(name, "parent cycle/depth");
            var model = json(data, current);
            chain.add(model);
            if (!model.has("parent")) break;
            String parent = location(model.get("parent").getAsString(), name);
            current = "assets/" + parent.replace(":", "/models/") + ".json";
            data = resources.read(current); // Never access host files or fetch unknown vanilla/external parents.
        }
        var textures = new TreeMap<String, String>();
        JsonArray elements = new JsonArray();
        boolean ao = true;
        String light = "side";
        for (int i = chain.size() - 1; i >= 0; i--) {
            var model = chain.get(i);
            if (model.has("textures")) for (var entry : model.getAsJsonObject("textures").entrySet()) textures.put(entry.getKey(), entry.getValue().getAsString());
            if (model.has("elements") && !model.getAsJsonArray("elements").isEmpty()) elements = model.getAsJsonArray("elements");
            if (model.has("ambientocclusion")) ao = model.get("ambientocclusion").getAsBoolean();
            if (model.has("gui_light")) light = model.get("gui_light").getAsString();
        }
        if (elements.size() > 256) throw unreadable(name, "element count");
        var result = new TreeMap<String, String>();
        result.put("ambientocclusion", Boolean.toString(ao));
        result.put("gui_light", light);
        result.put("elements.count", Integer.toString(elements.size()));
        // PacketEventsTransport sends display metadata 24=0 (NONE): GUI/hand/fixed transforms are unused.
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i).getAsJsonObject();
            String prefix = "elements[" + i + "].";
            double[] from = vector(element.get("from"), 3, name);
            double[] to = vector(element.get("to"), 3, name);
            result.put(prefix + "from", numbers(from));
            result.put(prefix + "to", numbers(to));
            result.put(prefix + "shade", Boolean.toString(!element.has("shade") || element.get("shade").getAsBoolean()));
            String shadeDirection = element.has("shade_direction_override") ? element.get("shade_direction_override").getAsString() : "none";
            if (!java.util.Set.of("none", "up", "down", "north", "south", "east", "west").contains(shadeDirection)) throw unreadable(name, "shade direction");
            result.put(prefix + "shade_direction_override", shadeDirection);
            result.put(prefix + "light_emission", number(element, "light_emission", 0, name));
            result.put(prefix + "rotation", "identity");
            if (element.has("rotation")) {
                var rotation = element.getAsJsonObject("rotation");
                double angle = Double.parseDouble(number(rotation, "angle", 0, name));
                if (angle != 0) {
                    result.put(prefix + "rotation", rotation.get("axis").getAsString() + ":" + angle);
                    result.put(prefix + "rotation.origin", numbers(vector(rotation.get("origin"), 3, name)));
                    result.put(prefix + "rotation.rescale", Boolean.toString(rotation.has("rescale") && rotation.get("rescale").getAsBoolean()));
                }
            }
            var faces = element.getAsJsonObject("faces");
            result.put(prefix + "faces", new java.util.TreeSet<>(faces.keySet()).toString());
            for (var entry : faces.entrySet()) {
                String direction = entry.getKey();
                var face = entry.getValue().getAsJsonObject();
                String field = prefix + "faces." + direction + ".";
                String sprite = texture(face.get("texture").getAsString(), textures, name);
                result.put(field + "texture", sprites.resolve(sprite));
                double[] uv = face.has("uv") ? vector(face.get("uv"), 4, name) : defaultUv(direction, from, to, name);
                result.put(field + "uv", numbers(uv));
                result.put(field + "rotation", number(face, "rotation", 0, name));
                result.put(field + "tintindex", number(face, "tintindex", -1, name));
                result.put(field + "cullface", face.has("cullface") ? face.get("cullface").getAsString() : "none");
            }
        }
        return result;
    }
    private String texture(String value, Map<String, String> textures, String name) throws IOException {
        var seen = new HashSet<String>();
        while (value.startsWith("#")) {
            if (!seen.add(value) || seen.size() > 32) throw unreadable(name, "texture variable cycle/depth");
            value = textures.get(value.substring(1));
            if (value == null) throw unreadable(name, "unresolved texture variable");
        }
        return location(value, name);
    }
    private double[] defaultUv(String direction, double[] a, double[] b, String name) throws IOException {
        return switch (direction) {
            case "down" -> new double[] {a[0], 16 - b[2], b[0], 16 - a[2]};
            case "up" -> new double[] {a[0], a[2], b[0], b[2]};
            case "north" -> new double[] {16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]};
            case "south" -> new double[] {a[0], 16 - b[1], b[0], 16 - a[1]};
            case "west" -> new double[] {a[2], 16 - b[1], b[2], 16 - a[1]};
            case "east" -> new double[] {16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]};
            default -> throw unreadable(name, "face direction");
        };
    }
    private double[] vector(JsonElement value, int size, String name) throws IOException {
        var array = value.getAsJsonArray();
        if (array.size() != size) throw unreadable(name, "vector size");
        double[] result = new double[size];
        for (int i = 0; i < size; i++) result[i] = finite(array.get(i), name);
        return result;
    }
    private String numbers(double[] values) { return java.util.Arrays.toString(values); }
    private String number(JsonObject object, String field, double fallback, String name) throws IOException {
        return Double.toString(object.has(field) ? finite(object.get(field), name) : fallback);
    }
    private double finite(JsonElement value, String name) throws IOException {
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || Math.abs(number) > 1000000) throw unreadable(name, "numeric bound");
        return number == 0 ? 0 : number;
    }
    static String location(String raw, String name) throws IOException {
        String key = raw.contains(":") ? raw : "minecraft:" + raw;
        if (key.length() > 256 || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || key.contains("..") || key.contains(":/")) throw unreadable(name, "invalid resource location");
        return key;
    }
    static void compare(String category, String name, Map<String, String> expected, Map<String, String> actual) throws PackProblem {
        var keys = new java.util.TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        var differences = new StringBuilder();
        int count = 0;
        for (String key : keys) if (!java.util.Objects.equals(expected.get(key), actual.get(key))) {
            if (count++ < 4) differences.append(key).append(" expected=").append(expected.getOrDefault(key, "absent"))
                    .append(" compiled=").append(actual.getOrDefault(key, "absent")).append("; ");
        }
        if (count > 0) throw new PackProblem(Code.WELLUI_RESOURCE_SHADOWED, name,
                category + " WELLUI_RENDERING_SEMANTICS_CHANGED (" + count + " fields): " + differences);
    }
    static JsonObject json(byte[] bytes, String name) throws IOException {
        if (bytes.length > 1024 * 1024) throw unreadable(name, "JSON size");
        try (var reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            var result = value(reader, 0, new int[] {0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return result.getAsJsonObject();
        } catch (RuntimeException | IOException malformed) { throw unreadable(name, "JSON syntax/depth/size/duplicate field"); }
    }
    private static JsonElement value(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 32 || ++nodes[0] > 32768) throw new IOException();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException();
                    object.add(key, value(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var array = new JsonArray();
                while (reader.hasNext()) array.add(value(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield com.google.gson.JsonNull.INSTANCE;
            }
            default -> throw new IOException();
        };
    }
    private static PackProblem unreadable(String name, String reason) { return new PackProblem(Code.ITEMSADDER_OUTPUT_UNREADABLE, name, "MODEL_RESOLUTION " + reason); }
}
