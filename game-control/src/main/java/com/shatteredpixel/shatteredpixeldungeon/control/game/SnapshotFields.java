package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.lang.reflect.Field;
import java.util.Map;

/** Field reads only: never invokes game getters, constructors or Bundle serialization. */
final class SnapshotFields {
    private SnapshotFields() {}

    static Object read(Object instance, String name) {
        Class<?> type = instance instanceof Class<?> ? (Class<?>) instance : instance.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance instanceof Class<?> ? null : instance);
            } catch (NoSuchFieldException ignored) {
                // Fields inherited from the model are part of the explicit adapter contract.
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Snapshot field unavailable", failure);
            }
        }
        throw new IllegalStateException("Snapshot field unavailable: " + type.getName() + "." + name);
    }

    static int integer(Object object, String field) {
        return ((Number) read(object, field)).intValue();
    }

    static Map<String, Object> map(Object... entries) {
        return com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map(entries);
    }
}
