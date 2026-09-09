package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.glwrap.Vertexbuffer;
import com.watabou.noosa.Tilemap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TilemapDrawObserverTest {
    @Test void visitsOnlyNonzeroExistingQuadsWithoutReadingModelDataOrMovingBufferPosition() throws Exception {
        Tilemap source = allocate(Tilemap.class);
        float[] geometry = new float[64];
        quad(geometry, 0, 0, 0, 16, 16);
        // Second tile is the original renderer's zero-geometry marker, even though data says occupied.
        quad(geometry, 2, 32, 0, 48, 16);
        quad(geometry, 3, Float.NaN, 0, 64, 16);
        FloatBuffer quads = FloatBuffer.wrap(geometry); quads.position(7); quads.mark();
        set(source, "quads", quads); set(source, "buffer", allocate(Vertexbuffer.class)); set(source, "size", 4);
        set(source, "data", new int[]{-1, 42, -1, 42});
        for (int repeat = 0; repeat < 10; repeat++) {
            List<float[]> read = new ArrayList<>();
            source.visitDrawnTiles((l,t,r,b) -> read.add(new float[]{l,t,r,b}));
            assertEquals(2, read.size());
            assertArrayEquals(new float[]{0,0,16,16}, read.get(0));
            assertArrayEquals(new float[]{32,0,48,16}, read.get(1));
            assertEquals(7, quads.position()); quads.reset();
            assertArrayEquals(geometry, quads.array());
        }
    }

    @Test void noUploadOrZeroQuadsCannotBeCalledDisplayed() throws Exception {
        Tilemap source = allocate(Tilemap.class);
        set(source, "quads", FloatBuffer.wrap(new float[16])); set(source, "size", 1);
        source.visitDrawnTiles((l,t,r,b) -> fail("No uploaded buffer"));
        set(source, "buffer", allocate(Vertexbuffer.class));
        source.visitDrawnTiles((l,t,r,b) -> fail("Zero-size render geometry"));
    }

    @Test void reviveClearsPriorTilemapObserver() throws Exception {
        Tilemap source = allocate(Tilemap.class);
        source.observeDraw(map -> fail("Old callback must not survive reuse"));
        source.revive();
        Field observer=Tilemap.class.getDeclaredField("drawObserver"); observer.setAccessible(true);
        assertNull(observer.get(source));
    }

    private static void quad(float[] a,int index,float l,float t,float r,float b) {
        int offset=index*16; a[offset]=l;a[offset+1]=t;a[offset+8]=r;a[offset+9]=b;
    }
    private static void set(Object target,String name,Object value)throws Exception {Field f=Tilemap.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @SuppressWarnings("unchecked") private static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("jdk.internal.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return (T)unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
    }
}
