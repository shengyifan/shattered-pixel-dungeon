package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.util.WeakIdentityRegistry;
import com.watabou.noosa.Gizmo;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic queue simulation, never a System.gc timing assertion. */
class UiBridgeIdentityTest {
    @Test void controlAndCallbackRegistriesDoNotOwnTheirKeysOrRecycleIdentities() throws Exception {
        UiBridge bridge=new UiBridge(()->null);
        verifyRegistry(bridge,"identities","id",Gizmo.class,new Gizmo(),new Gizmo());
        verifyRegistry(bridge,"callbackIdentities","callbackId",Object.class,new Object(),new Object());
    }

    private static void verifyRegistry(UiBridge bridge,String fieldName,String methodName,Class<?> argument,
                                       Object first,Object next) throws Exception {
        Method allocate=UiBridge.class.getDeclaredMethod(methodName,argument);allocate.setAccessible(true);
        String original=(String)allocate.invoke(bridge,first);
        assertEquals(original,allocate.invoke(bridge,first));
        Field field=UiBridge.class.getDeclaredField(fieldName);field.setAccessible(true);
        assertInstanceOf(WeakIdentityRegistry.class,field.get(bridge));
        @SuppressWarnings("unchecked") WeakIdentityRegistry<Object> registry=(WeakIdentityRegistry<Object>)field.get(bridge);
        Field valuesField=WeakIdentityRegistry.class.getDeclaredField("values");valuesField.setAccessible(true);
        Map<?,?> values=(Map<?,?>)valuesField.get(registry);
        Reference<?> key=(Reference<?>)values.keySet().stream()
                .filter(candidate->((Reference<?>)candidate).get()==first).findFirst().orElseThrow();
        assertTrue(values.values().stream().allMatch(value->value instanceof String || value instanceof Long));
        key.clear();assertTrue(key.enqueue());
        assertNull(registry.get(first));assertEquals(0,values.size());
        String replacement=(String)allocate.invoke(bridge,next);
        assertNotEquals(original,replacement);
        assertEquals(replacement,allocate.invoke(bridge,next));
        registry.clear();
        assertNotEquals(replacement,allocate.invoke(bridge,next),"Clearing a scene never recycles its IDs");
    }
}
