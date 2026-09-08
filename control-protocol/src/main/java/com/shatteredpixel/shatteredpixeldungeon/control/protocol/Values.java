package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Values {
    private Values() {}

    public static Map<String, Object> map(Object... alternating) {
        if (alternating.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < alternating.length; i += 2) {
            if (!(alternating[i] instanceof String)) throw new IllegalArgumentException("JSON keys must be strings");
            String key = (String) alternating[i];
            if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
            result.put(key, alternating[i + 1]);
        }
        return result;
    }

    public static List<Object> list(Object... values) {
        List<Object> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(value);
        return result;
    }
}
