package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Never initialize a game class merely to inspect it. Unknown means do not read its statics. */
final class ClassInitializationProbe {
    private final Object unsafe;
    private final Method shouldBeInitialized;

    ClassInitializationProbe() {
        Object foundUnsafe = null;
        Method foundMethod = null;
        for (String implementation : new String[]{"sun.misc.Unsafe", "jdk.internal.misc.Unsafe"}) {
            try {
                Class<?> type = Class.forName(implementation);
                Method method = type.getMethod("shouldBeInitialized", Class.class);
                Field singleton = type.getDeclaredField("theUnsafe");
                singleton.setAccessible(true);
                foundUnsafe = singleton.get(null);
                foundMethod = method;
                break;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Some JDKs remove this API; others require an explicit module open.
            }
        }
        unsafe = foundUnsafe;
        shouldBeInitialized = foundMethod;
    }

    Boolean initialized(Class<?> type) {
        if (unsafe == null) return null;
        try {
            return !((Boolean) shouldBeInitialized.invoke(unsafe, type));
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return null;
        }
    }
}
