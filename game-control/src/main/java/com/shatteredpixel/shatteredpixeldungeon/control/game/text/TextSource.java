package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-safe immutable value tree. No game object, callback, or tracked String is retained. */
final class TextSource {
    final Map<String, Object> value;
    private TextSource(Map<String, Object> value) { this.value = value; }

    static TextSource of(String kind, Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        for (int i = 0; i < fields.length; i += 2) values.put((String) fields[i], freeze(fields[i + 1]));
        return new TextSource(Collections.unmodifiableMap(values));
    }

    static TextSource unknown(String reason) { return of("unavailable", "reason", reason); }
    static TextSource fromFrozen(Map<String, Object> value) { return new TextSource(value); }

    private static Object freeze(Object value) {
        if (value instanceof TextSource) return ((TextSource) value).value;
        // A new wrapper prevents the value tree from keeping the weak registry key alive.
        if (value instanceof String) return new String((String) value);
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(freeze(item));
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) return value;
        throw new IllegalArgumentException("Unsupported frozen text value");
    }
}
