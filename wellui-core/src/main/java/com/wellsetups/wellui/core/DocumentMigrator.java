package com.wellsetups.wellui.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Ordered, copy-on-write document migrations; unsupported future schemas fail explicitly. */
final class DocumentMigrator {
    private final Map<Integer, UnaryOperator<JsonObject>> steps = Map.of(0, this::versionZeroToOne);

    JsonObject migrate(JsonObject source) {
        JsonObject document = source.deepCopy();
        int version = readVersion(document);
        if (version < 0 || version > 1) throw new IllegalArgumentException("validation.schema");
        while (version < 1) {
            UnaryOperator<JsonObject> migration = steps.get(version);
            if (migration == null) throw new IllegalArgumentException("validation.migration_missing");
            document = migration.apply(document);
            int next = readVersion(document);
            if (next != version + 1) throw new IllegalStateException("validation.migration_sequence");
            version = next;
        }
        return document;
    }

    private int readVersion(JsonObject object) {
        JsonElement value = object.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("validation.schema");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("validation.schema", exception);
        }
    }

    // Version zero used "components" at the document and panel levels.
    private JsonObject versionZeroToOne(JsonObject document) {
        renameChildren(document);
        document.addProperty("schemaVersion", 1);
        return document;
    }

    private void renameChildren(JsonObject node) {
        if (node.has("components")) {
            if (node.has("children")) throw new IllegalArgumentException("validation.migration_conflict");
            node.add("children", node.remove("components"));
        }
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                if (child.isJsonObject()) renameChildren(child.getAsJsonObject());
            }
        }
    }
}
